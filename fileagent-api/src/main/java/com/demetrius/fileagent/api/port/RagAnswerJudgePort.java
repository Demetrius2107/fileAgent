package com.demetrius.fileagent.api.port;

import java.util.List;

/**
 * RAG 回答语义评判端口。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-09
 */
public interface RagAnswerJudgePort {

    Result judge(Request request);

    record Request(
            String question,
            boolean shouldAnswer,
            List<String> requiredFacts,
            List<String> forbiddenFacts,
            String answer,
            List<Evidence> evidence
    ) {
        public Request {
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record Evidence(String filename, String content) {
    }

    record Result(
            Decision decision,
            String decisionReason,
            boolean hasUnsupportedClaims,
            String unsupportedClaimsReason,
            List<FactAssessment> requiredFacts,
            List<FactAssessment> forbiddenFacts,
            String rawResponse,
            long durationMs
    ) {
        public Result(Decision decision,
                      String decisionReason,
                      boolean hasUnsupportedClaims,
                      String unsupportedClaimsReason,
                      List<FactAssessment> requiredFacts,
                      List<FactAssessment> forbiddenFacts,
                      long durationMs) {
            this(decision, decisionReason, hasUnsupportedClaims, unsupportedClaimsReason,
                    requiredFacts, forbiddenFacts, null, durationMs);
        }

        public Result {
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
        }
    }

    final class JudgeException extends RuntimeException {

        private final String rawResponse;

        public JudgeException(String message, String rawResponse, Throwable cause) {
            super(message, cause);
            this.rawResponse = rawResponse;
        }

        public String rawResponse() {
            return rawResponse;
        }
    }

    record FactAssessment(String fact, boolean matched, String reason) {
    }

    enum Decision {
        ANSWERED,
        REFUSED
    }
}
