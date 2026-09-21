package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;

import java.util.List;

/**
 * 一道 Agent 评测题的实际运行观测：既含 Agent 行为（步骤/调用/引用/终态），
 * 也含复用 Judge 得到的答案质量评判。{@code judgeDecision == null} 表示 Judge 失败；
 * {@code retrieval == null} 表示该 Run 未执行结构化检索（非自适应运行）。
 */
public record AgentEvaluationObservation(
        String caseId,
        String category,
        String question,
        String answer,
        boolean refused,
        List<String> retrievedFilenames,
        List<String> citedFilenames,
        int stepCount,
        int modelCallCount,
        List<String> toolCalls,
        long durationMs,
        AgentRunStatus terminalStatus,
        String failureCode,
        RagAnswerJudgePort.Decision judgeDecision,
        boolean judgeHasUnsupportedClaims,
        List<RagAnswerJudgePort.FactAssessment> judgeRequiredFacts,
        List<RagAnswerJudgePort.FactAssessment> judgeForbiddenFacts,
        String error,
        AgentAnswerEvaluationPort.RetrievalObservation retrieval
) {

    public AgentEvaluationObservation {
        retrievedFilenames = retrievedFilenames == null ? List.of() : List.copyOf(retrievedFilenames);
        citedFilenames = citedFilenames == null ? List.of() : List.copyOf(citedFilenames);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        judgeRequiredFacts = judgeRequiredFacts == null ? List.of() : List.copyOf(judgeRequiredFacts);
        judgeForbiddenFacts = judgeForbiddenFacts == null ? List.of() : List.copyOf(judgeForbiddenFacts);
    }

    /** 兼容构造：无检索溯源（非自适应运行）。 */
    public AgentEvaluationObservation(String caseId,
                                      String category,
                                      String question,
                                      String answer,
                                      boolean refused,
                                      List<String> retrievedFilenames,
                                      List<String> citedFilenames,
                                      int stepCount,
                                      int modelCallCount,
                                      List<String> toolCalls,
                                      long durationMs,
                                      AgentRunStatus terminalStatus,
                                      String failureCode,
                                      RagAnswerJudgePort.Decision judgeDecision,
                                      boolean judgeHasUnsupportedClaims,
                                      List<RagAnswerJudgePort.FactAssessment> judgeRequiredFacts,
                                      List<RagAnswerJudgePort.FactAssessment> judgeForbiddenFacts,
                                      String error) {
        this(caseId, category, question, answer, refused, retrievedFilenames, citedFilenames,
                stepCount, modelCallCount, toolCalls, durationMs, terminalStatus, failureCode,
                judgeDecision, judgeHasUnsupportedClaims, judgeRequiredFacts, judgeForbiddenFacts,
                error, null);
    }

    public boolean agentSucceeded() {
        return error == null && terminalStatus == AgentRunStatus.SUCCEEDED;
    }

    public boolean judgeSucceeded() {
        return agentSucceeded() && judgeDecision != null;
    }
}
