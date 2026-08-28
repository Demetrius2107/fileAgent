package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agentic RAG 流式聊天编排：检索规划 -> 最多两轮检索 -> Prompt 组装 -> 模型回答 -> 落库。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppServiceImpl implements ChatAppService {

    private static final String NO_KNOWLEDGE_NOTICE = "未检索到相关知识库内容，以下回答来自模型通用知识。";
    private static final String ANSWER_SOURCE_KNOWLEDGE = "KNOWLEDGE";
    private static final String ANSWER_SOURCE_MODEL_GENERAL = "MODEL_GENERAL";
    private static final String CODE_KNOWLEDGE_SEARCH_FAILED = "KNOWLEDGE_SEARCH_FAILED";
    private static final String CODE_MODEL_STREAM_FAILED = "MODEL_STREAM_FAILED";

    private final SessionQueryPort sessionQueryPort;
    private final SessionMessagePort sessionMessagePort;
    private final KnowledgeSearchPort knowledgeSearchPort;
    private final RagRetrievalPlanner ragRetrievalPlanner;
    private final RagPromptBuilder ragPromptBuilder;
    private final StreamingChatClient streamingChatClient;

    @Value("${fileagent.chat-history-limit:10}")
    private int chatHistoryLimit;

    @Override
    public Flux<ChatStreamEvent> chat(Long sessionId, String prompt) {
        if (sessionId == null) {
            throw new BizException("sessionId 不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BizException("prompt 不能为空");
        }
        if (!sessionQueryPort.exists(sessionId)) {
            throw new BizException(404, "会话不存在");
        }
        return Flux.defer(() -> orchestrate(sessionId, prompt.trim()));
    }

    private Flux<ChatStreamEvent> orchestrate(Long sessionId, String prompt) {
        // 读取历史须在保存当前 USER 之前，避免当前问题在 Prompt 中出现两次
        List<MessageDto> history = tail(sessionQueryPort.listMessages(sessionId), chatHistoryLimit);
        sessionMessagePort.append(sessionId, MessageType.USER, prompt);

        RagRetrievalPlanner.RetrievalPlan retrievalPlan = ragRetrievalPlanner.plan(history, prompt);
        List<KnowledgeSearchPort.KnowledgeHit> hits;
        try {
            hits = retrieveKnowledge(prompt, retrievalPlan);
        } catch (Exception e) {
            log.warn("知识检索失败: sessionId={}", sessionId, e);
            return Flux.just(ChatStreamEvent.error(CODE_KNOWLEDGE_SEARCH_FAILED, "知识检索失败，请稍后重试"));
        }
        boolean knowledgeMiss = hits.isEmpty();
        boolean showKnowledgeMissNotice = retrievalPlan.needRetrieval() && knowledgeMiss;

        Prompt modelPrompt = ragPromptBuilder.build(history, hits, prompt);
        StringBuilder answer = new StringBuilder();

        Flux<ChatStreamEvent> messageEvents = streamingChatClient.stream(modelPrompt)
                .doOnNext(answer::append)
                .map(ChatStreamEvent::message);
        if (showKnowledgeMissNotice) {
            messageEvents = Flux.concat(
                    Flux.just(ChatStreamEvent.message(NO_KNOWLEDGE_NOTICE)),
                    messageEvents);
        }

        return messageEvents
                // 模型零片段正常结束视为模型错误，不落空消息
                .switchIfEmpty(Flux.error(new IllegalStateException("模型未返回任何内容")))
                // 模型完整结束后才保存 ASSISTANT 并收尾；取消/异常不会进入该分支
                .concatWith(Flux.defer(() -> {
                    String fullAnswer = showKnowledgeMissNotice
                            ? NO_KNOWLEDGE_NOTICE + "\n" + answer
                            : answer.toString();
                    Long assistantMessageId = sessionMessagePort.append(sessionId, MessageType.ASSISTANT, fullAnswer);
                    String answerSource = knowledgeMiss ? ANSWER_SOURCE_MODEL_GENERAL : ANSWER_SOURCE_KNOWLEDGE;
                    List<String> files = hits.stream()
                            .map(KnowledgeSearchPort.KnowledgeHit::filename)
                            .collect(Collectors.toCollection(LinkedHashSet::new))
                            .stream().toList();
                    return Flux.just(
                            ChatStreamEvent.sources(answerSource, files),
                            ChatStreamEvent.done(assistantMessageId));
                }))
                .onErrorResume(e -> {
                    log.warn("模型流式调用失败: sessionId={}", sessionId, e);
                    return Flux.just(ChatStreamEvent.error(CODE_MODEL_STREAM_FAILED, "模型调用失败，请稍后重试"));
                });
    }

    private List<KnowledgeSearchPort.KnowledgeHit> retrieveKnowledge(
            String question, RagRetrievalPlanner.RetrievalPlan plan) {
        if (!plan.needRetrieval()) {
            return List.of();
        }
        List<KnowledgeSearchPort.KnowledgeHit> firstHits = knowledgeSearchPort.search(plan.standaloneQuery());
        return ragRetrievalPlanner.retryQuery(question, plan, firstHits)
                .map(retryQuery -> mergeRetryHits(firstHits, retryQuery))
                .orElse(firstHits);
    }

    private List<KnowledgeSearchPort.KnowledgeHit> mergeRetryHits(
            List<KnowledgeSearchPort.KnowledgeHit> firstHits, String retryQuery) {
        try {
            List<KnowledgeSearchPort.KnowledgeHit> retryHits = knowledgeSearchPort.search(retryQuery);
            Map<String, KnowledgeSearchPort.KnowledgeHit> uniqueHits = new LinkedHashMap<>();
            for (KnowledgeSearchPort.KnowledgeHit hit : firstHits) {
                uniqueHits.put(hit.filename() + "\n" + hit.content(), hit);
            }
            for (KnowledgeSearchPort.KnowledgeHit hit : retryHits) {
                uniqueHits.putIfAbsent(hit.filename() + "\n" + hit.content(), hit);
            }
            return List.copyOf(uniqueHits.values());
        } catch (Exception e) {
            log.warn("补检索失败，继续使用首轮结果: retryQuery={}", retryQuery, e);
            return firstHits;
        }
    }

    private List<MessageDto> tail(List<MessageDto> messages, int limit) {
        if (messages == null) {
            return List.of();
        }
        if (messages.size() <= limit) {
            return messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }
}
