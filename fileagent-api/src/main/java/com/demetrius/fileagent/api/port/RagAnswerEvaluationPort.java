package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.MessageType;

import java.util.List;

/**
 * RAG 端到端评测端口：复用正式检索与回答配置，但不创建或持久化业务会话。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-09
 */
public interface RagAnswerEvaluationPort {

    Result evaluate(Query query);

    record Query(
            String question,
            List<HistoryMessage> history,
            String ragName,
            String knowledgeTag,
            Long fileId
    ) {
        public Query {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    record HistoryMessage(MessageType role, String content) {
    }

    record Result(
            String answer,
            List<KnowledgeSearchPort.KnowledgeHit> retrieved,
            List<KnowledgeSearchPort.KnowledgeHit> citations,
            long durationMs
    ) {
        public Result {
            retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
