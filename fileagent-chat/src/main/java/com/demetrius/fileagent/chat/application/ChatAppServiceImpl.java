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

import java.util.List;

/**
 * RAG 流式聊天编排：多轮问题改写 -> 混合检索 -> Prompt 组装 -> 模型回答 -> 落库。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppServiceImpl implements ChatAppService {

    private static final String NO_KNOWLEDGE_NOTICE = "未检索到可用于回答该问题的知识库资料，无法根据现有资料确认。请提供相关文件或咨询对应负责人。";
    private static final String ANSWER_SOURCE_KNOWLEDGE = "KNOWLEDGE";
    private static final String ANSWER_SOURCE_KNOWLEDGE_INSUFFICIENT = "KNOWLEDGE_INSUFFICIENT";
    private static final String CODE_KNOWLEDGE_SEARCH_FAILED = "KNOWLEDGE_SEARCH_FAILED";
    private static final String CODE_MODEL_STREAM_FAILED = "MODEL_STREAM_FAILED";

    private final SessionQueryPort sessionQueryPort;
    private final SessionMessagePort sessionMessagePort;
    private final KnowledgeSearchPort knowledgeSearchPort;
    private final RagQueryRewriter ragQueryRewriter;
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

        String retrievalQuery = ragQueryRewriter.rewrite(history, prompt);
        List<KnowledgeSearchPort.KnowledgeHit> hits;
        try {
            hits = knowledgeSearchPort.search(KnowledgeSearchPort.SearchQuery.of(retrievalQuery));
        } catch (Exception e) {
            log.warn("知识检索失败: sessionId={}", sessionId, e);
            return Flux.just(ChatStreamEvent.error(CODE_KNOWLEDGE_SEARCH_FAILED, "知识检索失败，请稍后重试"));
        }
        if (hits.isEmpty()) {
            Long assistantMessageId = sessionMessagePort.append(sessionId, MessageType.ASSISTANT, NO_KNOWLEDGE_NOTICE);
            return Flux.just(
                    ChatStreamEvent.message(NO_KNOWLEDGE_NOTICE),
                    ChatStreamEvent.sources(ANSWER_SOURCE_KNOWLEDGE_INSUFFICIENT, List.of()),
                    ChatStreamEvent.done(assistantMessageId));
        }

        Prompt modelPrompt = ragPromptBuilder.build(history, hits, prompt);
        StringBuilder answer = new StringBuilder();

        Flux<ChatStreamEvent> messageEvents = streamingChatClient.stream(modelPrompt)
                .doOnNext(answer::append)
                .map(ChatStreamEvent::message);

        return messageEvents
                // 模型零片段正常结束视为模型错误，不落空消息
                .switchIfEmpty(Flux.error(new IllegalStateException("模型未返回任何内容")))
                // 模型完整结束后才保存 ASSISTANT 并收尾；取消/异常不会进入该分支
                .concatWith(Flux.defer(() -> {
                    String fullAnswer = answer.toString();
                    Long assistantMessageId = sessionMessagePort.append(sessionId, MessageType.ASSISTANT, fullAnswer);
                    List<String> files = AnswerCitationExtractor.extract(fullAnswer, hits).stream()
                            .map(KnowledgeSearchPort.KnowledgeHit::filename)
                            .toList();
                    return Flux.just(
                            ChatStreamEvent.sources(ANSWER_SOURCE_KNOWLEDGE, files),
                            ChatStreamEvent.done(assistantMessageId));
                }))
                .onErrorResume(e -> {
                    log.warn("模型流式调用失败: sessionId={}", sessionId, e);
                    return Flux.just(ChatStreamEvent.error(CODE_MODEL_STREAM_FAILED, "模型调用失败，请稍后重试"));
                });
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
