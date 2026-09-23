package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent Run 聚合根：单机内存运行态与状态机。
 * <p>
 * 状态转移：{@code PENDING -> RUNNING -> SUCCEEDED/FAILED/CANCELLED/TIMED_OUT}。
 * 终态不可再转移。进程重启后状态自然丢失（Phase 1 显式限制，不伪装持久化）。
 */
public class AgentRun {

    public static final String KNOWLEDGE_SEARCH_FAILURE_CODE = "AGENT_KNOWLEDGE_SEARCH_FAILED";
    public static final String AGENT_RETRIEVAL_PLAN_INVALID = "AGENT_RETRIEVAL_PLAN_INVALID";

    /** 单次 Run 检索轮数上限（决策 2：最多 2 轮 search_docs），工具层在入口处据此拒绝第三轮。 */
    public static final int MAX_RETRIEVAL_ROUNDS = 2;
    private static final int MAX_INVALID_PLANS = 2;

    private final String runId;
    private final Long sessionId;
    private final String traceId;

    private AgentRunStatus status = AgentRunStatus.PENDING;
    private Instant startedAt;
    private Instant endedAt;
    private int stepCount;
    private int modelCallCount;
    private int historyCharacters;
    private int summaryCharacters;
    private int searchSnippetCharacters;
    private int documentReadCharacters;
    private int toolResultCharacters;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private boolean historyCompressed;
    private final Set<String> allowedChunkIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> allowedFileIds = ConcurrentHashMap.newKeySet();
    private final List<String> budgetReasons = new CopyOnWriteArrayList<>();
    private final List<KnowledgeHit> retrievedHits = new CopyOnWriteArrayList<>();
    private volatile boolean cancelRequested;
    private Long assistantMessageId;
    private String failureCode;
    private volatile String pendingToolFailureCode;
    private volatile int lastToolResultCount;
    private final List<RetrievalExecution> retrievalExecutions = new CopyOnWriteArrayList<>();
    private volatile int invalidPlanCount;

    private AgentRun(String runId, Long sessionId, String traceId) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.traceId = traceId;
    }

    public static AgentRun pending(String runId, Long sessionId, String traceId) {
        return new AgentRun(runId, sessionId, traceId);
    }

    public void start(Instant now) {
        requireStatus(AgentRunStatus.PENDING);
        this.status = AgentRunStatus.RUNNING;
        this.startedAt = now;
    }

    public void succeed(Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.SUCCEEDED;
        this.endedAt = now;
    }

    public void fail(String code, Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.FAILED;
        this.failureCode = code;
        this.endedAt = now;
    }

    public void cancel(Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.CANCELLED;
        this.endedAt = now;
    }

    public void timeout(Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.TIMED_OUT;
        this.failureCode = "AGENT_RUN_TIMEOUT";
        this.endedAt = now;
    }

    /** 记录最终落库的 assistant 消息 ID（成功前调用）。 */
    public void recordAssistantMessage(Long messageId) {
        this.assistantMessageId = messageId;
    }

    /** 记录最近一次工具调用的结果数量（供 tool.completed 事件使用）。 */
    public void recordToolResult(int count) {
        this.lastToolResultCount = count;
    }

    /** 记录尚待运行时消费的工具失败，不改变当前 Run 终态。 */
    public void recordToolFailure(String code) {
        if (isRunning()) {
            this.pendingToolFailureCode = code;
        }
    }

    /**
     * 记录一次非法检索计划：Step 由运行时在工具调用开始时统一消耗，这里只计数；
     * 再次发生即终止 Run，并挂起工具失败码让运行时立即中断 Agent。
     */
    public void recordInvalidPlan(Instant now) {
        if (!isRunning()) {
            return;
        }
        invalidPlanCount++;
        if (invalidPlanCount >= MAX_INVALID_PLANS) {
            recordToolFailure(AGENT_RETRIEVAL_PLAN_INVALID);
            fail(AGENT_RETRIEVAL_PLAN_INVALID, now);
        }
    }

    /** 记录一轮结构化检索的执行溯源；超过 2 轮视为非法协议调用。 */
    public void recordRetrievalExecution(RetrievalExecution execution) {
        if (execution == null) {
            return;
        }
        if (retrievalExecutions.size() >= MAX_RETRIEVAL_ROUNDS) {
            throw new IllegalStateException("检索轮数超出上限: " + MAX_RETRIEVAL_ROUNDS);
        }
        retrievalExecutions.add(execution);
    }

    public void addAllowedChunk(String chunkId) {
        if (chunkId != null) {
            this.allowedChunkIds.add(chunkId);
        }
    }

    public void addAllowedFile(Long fileId) {
        if (fileId != null) {
            allowedFileIds.add(fileId);
        }
    }

    public boolean isAllowedFile(Long fileId) {
        return fileId != null && allowedFileIds.contains(fileId);
    }

    public void recordHistoryUsage(int historyCharacters, int summaryCharacters, boolean compressed) {
        this.historyCharacters = Math.max(0, historyCharacters);
        this.summaryCharacters = Math.max(0, summaryCharacters);
        this.historyCompressed = compressed;
    }

    public void recordSearchSnippetCharacters(int characters) {
        this.searchSnippetCharacters += Math.max(0, characters);
    }

    public void recordDocumentReadCharacters(int characters) {
        this.documentReadCharacters += Math.max(0, characters);
    }

    public void recordToolResultCharacters(int characters) {
        this.toolResultCharacters += Math.max(0, characters);
    }

    public void recordModelUsage(int inputTokens, int outputTokens, int totalTokens) {
        this.inputTokens += Math.max(0, inputTokens);
        this.outputTokens += Math.max(0, outputTokens);
        this.totalTokens += Math.max(0, totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
    }

    public void addBudgetReason(String reason) {
        if (reason != null && !reason.isBlank() && !budgetReasons.contains(reason)) {
            budgetReasons.add(reason);
        }
    }

    public boolean shouldStopToolExpansion(AgentRunBudget budget) {
        boolean stopped = false;
        if (toolResultCharacters >= budget.maxToolResultCharacters()) {
            addBudgetReason("TOOL_BUDGET_EXHAUSTED");
            stopped = true;
        }
        if (totalTokens >= budget.maxTotalTokens()) {
            addBudgetReason("TOKEN_BUDGET_EXHAUSTED");
            stopped = true;
        }
        return stopped;
    }

    /** 记录一次检索命中的完整片段（供离线评测收集检索证据）。 */
    public void addRetrievedHit(KnowledgeHit hit) {
        if (hit != null) {
            this.retrievedHits.add(hit);
        }
    }

    public boolean isAllowedChunk(String chunkId) {
        return chunkId != null && this.allowedChunkIds.contains(chunkId);
    }

    public void incrementStep() {
        this.stepCount++;
    }

    public void incrementModelCall() {
        this.modelCallCount++;
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return this.cancelRequested;
    }

    public boolean isRunning() {
        return this.status == AgentRunStatus.RUNNING;
    }

    public boolean isTerminal() {
        return this.status == AgentRunStatus.SUCCEEDED
                || this.status == AgentRunStatus.FAILED
                || this.status == AgentRunStatus.CANCELLED
                || this.status == AgentRunStatus.TIMED_OUT;
    }

    private void requireStatus(AgentRunStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("非法状态转移: 期望 " + expected + "，实际 " + this.status);
        }
    }

    public String runId() {
        return runId;
    }

    public Long sessionId() {
        return sessionId;
    }

    public String traceId() {
        return traceId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public int stepCount() {
        return stepCount;
    }

    public int modelCallCount() {
        return modelCallCount;
    }

    public Set<String> allowedChunkIds() {
        return Collections.unmodifiableSet(allowedChunkIds);
    }

    public Set<Long> allowedFileIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(allowedFileIds));
    }

    public int historyCharacters() {
        return historyCharacters;
    }

    public int summaryCharacters() {
        return summaryCharacters;
    }

    public int searchSnippetCharacters() {
        return searchSnippetCharacters;
    }

    public int documentReadCharacters() {
        return documentReadCharacters;
    }

    public int toolResultCharacters() {
        return toolResultCharacters;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public boolean historyCompressed() {
        return historyCompressed;
    }

    public List<String> budgetReasons() {
        return List.copyOf(budgetReasons);
    }

    public List<KnowledgeHit> retrievedHits() {
        return List.copyOf(retrievedHits);
    }

    public Long assistantMessageId() {
        return assistantMessageId;
    }

    public String failureCode() {
        return failureCode;
    }

    public String pendingToolFailureCode() {
        return pendingToolFailureCode;
    }

    public int lastToolResultCount() {
        return lastToolResultCount;
    }

    public List<RetrievalExecution> retrievalExecutions() {
        return List.copyOf(retrievalExecutions);
    }

    public int retrievalRoundCount() {
        return retrievalExecutions.size();
    }

    public int invalidPlanCount() {
        return invalidPlanCount;
    }

    /** 单轮结构化检索的执行溯源（供离线评测与轮数控制使用）。 */
    public record RetrievalExecution(
            RetrievalQueryType queryType,
            String strategyId,
            int plannedQueryCount,
            int executedQueries,
            List<Integer> perQueryHitCount,
            List<String> candidateChunkIds,
            List<String> finalChunkIds,
            boolean rerankRequested,
            boolean rerankApplied,
            String fallbackCode) {
        public RetrievalExecution {
            perQueryHitCount = perQueryHitCount == null ? List.of() : List.copyOf(perQueryHitCount);
            candidateChunkIds = candidateChunkIds == null ? List.of() : List.copyOf(candidateChunkIds);
            finalChunkIds = finalChunkIds == null ? List.of() : List.copyOf(finalChunkIds);
        }
    }
}
