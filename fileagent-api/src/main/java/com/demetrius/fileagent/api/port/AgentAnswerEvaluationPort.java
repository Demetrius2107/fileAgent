package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;

import java.util.List;

/**
 * Agent 回答评测端口：以评测模式运行一次受控的 Agent，返回完整观察数据。
 * <p>
 * 由 {@code fileagent-agent} 的 {@code AgentAnswerEvaluationService} 实现。与生产
 * {@link AgentRuntimePort} 不同，本端口专门服务于离线评测：不创建会话、不落库消息，
 * 只保留评测所需的受控观察（最终答案、检索命中、引用、步骤/模型调用数与终态）。
 */
public interface AgentAnswerEvaluationPort {

    /**
     * 以评测模式运行 Agent 并返回观察。
     *
     * @param query 评测输入
     * @return 完整观察结果
     */
    Result evaluate(Query query);

    /** 评测输入：与标准评测题对齐。 */
    record Query(
            String question,
            List<HistoryMessage> history,
            String ragName,
            String knowledgeTag,
            Long fileId) {
        public Query {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** 多轮历史消息。 */
    record HistoryMessage(MessageType role, String content) {
    }

    /**
     * Agent 评测观察结果。
     *
     * @param answer           最终答案文本（可能为空表示拒答）
     * @param refused          Agent 是否未给出答案（答案为空）
     * @param retrieved        Agent 检索命中的知识片段（含正文与来源，供 Judge 作证据）
     * @param citedFilenames   最终答案中引用的来源文件名
     * @param stepCount        工具调用步数
     * @param modelCallCount   模型调用次数
     * @param toolCalls        工具调用名称序列（用于工具白名单检查）
     * @param durationMs       运行耗时
     * @param terminalStatus   终态
     * @param failureCode      失败码（失败/超时/取消时非空）
     * @param retrieval        最后一轮自适应检索执行观察（非自适应运行为空）
     * @param retrievals       全部有效检索轮次（按执行顺序）
     * @param details           上下文预算与历史摘要观察
     */
    record Result(
            String answer,
            boolean refused,
            List<KnowledgeSearchPort.KnowledgeHit> retrieved,
            List<String> citedFilenames,
            int stepCount,
            int modelCallCount,
            List<String> toolCalls,
            long durationMs,
            AgentRunStatus terminalStatus,
            String failureCode,
            RetrievalObservation retrieval,
            List<RetrievalObservation> retrievals,
            EvaluationDetails details) {
        public Result {
            retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
            citedFilenames = citedFilenames == null ? List.of() : List.copyOf(citedFilenames);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            retrievals = retrievals == null || retrievals.isEmpty()
                    ? (retrieval == null ? List.of() : List.of(retrieval)) : List.copyOf(retrievals);
            retrieval = retrievals.isEmpty() ? null : retrievals.getLast();
            details = details == null ? EvaluationDetails.empty() : details;
        }

        public Result(String answer, boolean refused, List<KnowledgeSearchPort.KnowledgeHit> retrieved,
                      List<String> citedFilenames, int stepCount, int modelCallCount,
                      List<String> toolCalls, long durationMs, AgentRunStatus terminalStatus,
                      String failureCode, RetrievalObservation retrieval) {
            this(answer, refused, retrieved, citedFilenames, stepCount, modelCallCount, toolCalls,
                    durationMs, terminalStatus, failureCode, retrieval, null, EvaluationDetails.empty());
        }

        public Result(String answer, boolean refused, List<KnowledgeSearchPort.KnowledgeHit> retrieved,
                      List<String> citedFilenames, int stepCount, int modelCallCount,
                      List<String> toolCalls, long durationMs, AgentRunStatus terminalStatus,
                      String failureCode, RetrievalObservation retrieval,
                      List<RetrievalObservation> retrievals) {
            this(answer, refused, retrieved, citedFilenames, stepCount, modelCallCount, toolCalls,
                    durationMs, terminalStatus, failureCode, retrieval, retrievals,
                    EvaluationDetails.empty());
        }
    }

    /**
     * 单次评测的上下文预算观察。字符数按 Java code point 口径记录，Token 数来自模型 usage。
     * 布尔字段由运行时根据实际组装和预算收口结果给出，不从答案文本反推。
     */
    record EvaluationDetails(
            int historyCharacters,
            int summaryCharacters,
            int searchSnippetCharacters,
            int documentReadCharacters,
            int toolResultCharacters,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            boolean historyCompressed,
            List<String> budgetReasons,
            boolean promptPreserved,
            boolean toolBudgetCompliant,
            boolean budgetExhaustionCompleted,
            boolean summaryHasUnsupportedClaims,
            boolean summaryJudgeCompleted,
            String summary,
            Long summaryThroughMessageId) {
        public EvaluationDetails {
            budgetReasons = budgetReasons == null ? List.of() : List.copyOf(budgetReasons);
            historyCharacters = Math.max(0, historyCharacters);
            summaryCharacters = Math.max(0, summaryCharacters);
            searchSnippetCharacters = Math.max(0, searchSnippetCharacters);
            documentReadCharacters = Math.max(0, documentReadCharacters);
            toolResultCharacters = Math.max(0, toolResultCharacters);
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            totalTokens = Math.max(0, totalTokens);
        }

        public EvaluationDetails(int historyCharacters,
                                 int summaryCharacters,
                                 int searchSnippetCharacters,
                                 int documentReadCharacters,
                                 int toolResultCharacters,
                                 long inputTokens,
                                 long outputTokens,
                                 long totalTokens,
                                 boolean historyCompressed,
                                 List<String> budgetReasons,
                                 boolean promptPreserved,
                                 boolean toolBudgetCompliant,
                                 boolean budgetExhaustionCompleted,
                                 boolean summaryHasUnsupportedClaims) {
            this(historyCharacters, summaryCharacters, searchSnippetCharacters, documentReadCharacters,
                    toolResultCharacters, inputTokens, outputTokens, totalTokens, historyCompressed,
                    budgetReasons, promptPreserved, toolBudgetCompliant, budgetExhaustionCompleted,
                    summaryHasUnsupportedClaims, false, null, null);
        }

        public EvaluationDetails withSummaryJudge(boolean hasUnsupportedClaims) {
            return new EvaluationDetails(historyCharacters, summaryCharacters, searchSnippetCharacters,
                    documentReadCharacters, toolResultCharacters, inputTokens, outputTokens, totalTokens,
                    historyCompressed, budgetReasons, promptPreserved, toolBudgetCompliant,
                    budgetExhaustionCompleted, hasUnsupportedClaims, true, summary, summaryThroughMessageId);
        }

        public static EvaluationDetails empty() {
            return new EvaluationDetails(0, 0, 0, 0, 0, 0, 0, 0,
                    false, List.of(), false, false, false, false, false, null, null);
        }
    }

    /**
     * 自适应检索执行观察（Phase 2A）：一次运行的检索计划与执行汇总，
     * 由 Agent Run 的 RetrievalExecution 记录映射，供评测计算自适应指标。
     */
    record RetrievalObservation(
            RetrievalQueryType queryType,
            String strategyId,
            int plannedQueryCount,
            int executedQueryCount,
            List<Integer> perQueryHitCounts,
            List<String> candidateChunkIds,
            List<String> finalChunkIds,
            boolean rerankRequested,
            boolean rerankApplied,
            String fallbackCode) {
        public RetrievalObservation {
            perQueryHitCounts = perQueryHitCounts == null ? List.of() : List.copyOf(perQueryHitCounts);
            candidateChunkIds = candidateChunkIds == null ? List.of() : List.copyOf(candidateChunkIds);
            finalChunkIds = finalChunkIds == null ? List.of() : List.copyOf(finalChunkIds);
        }
    }
}
