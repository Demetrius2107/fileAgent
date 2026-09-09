package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerEvaluationPort;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用当前生效的 RAG 链路生成评测回答，不读写用户会话与消息。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-09
 */
@Service
@RequiredArgsConstructor
public class RagAnswerEvaluationService implements RagAnswerEvaluationPort {

    private final KnowledgeSearchPort knowledgeSearchPort;
    private final RagQueryRewriter ragQueryRewriter;
    private final RagPromptBuilder ragPromptBuilder;
    private final StreamingChatClient streamingChatClient;

    @Override
    public Result evaluate(Query query) {
        long startedAt = System.nanoTime();
        List<MessageDto> history = toHistory(query.history());
        String retrievalQuery = ragQueryRewriter.rewrite(history, query.question());
        List<KnowledgeSearchPort.KnowledgeHit> hits = knowledgeSearchPort.search(
                new KnowledgeSearchPort.SearchQuery(retrievalQuery,
                        query.ragName(), query.knowledgeTag(), query.fileId()));
        Prompt prompt = ragPromptBuilder.build(history, hits, query.question());
        String answer = streamingChatClient.call(prompt);
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("模型未返回评测回答");
        }
        String normalizedAnswer = answer.trim();
        return new Result(normalizedAnswer, hits,
                distinctSources(hits), elapsedMillis(startedAt));
    }

    private static List<MessageDto> toHistory(List<HistoryMessage> history) {
        return history.stream()
                .map(message -> new MessageDto(null, null, message.role(), message.content(), null, null))
                .toList();
    }

    private static List<KnowledgeSearchPort.KnowledgeHit> distinctSources(
            List<KnowledgeSearchPort.KnowledgeHit> hits) {
        Map<String, KnowledgeSearchPort.KnowledgeHit> sources = new LinkedHashMap<>();
        for (KnowledgeSearchPort.KnowledgeHit hit : hits) {
            String key = hit.filename() == null ? "chunk:" + hit.chunkId() : "file:" + hit.filename();
            sources.putIfAbsent(key, hit);
        }
        return List.copyOf(sources.values());
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
