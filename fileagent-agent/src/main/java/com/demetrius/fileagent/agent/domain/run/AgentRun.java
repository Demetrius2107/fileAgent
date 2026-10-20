package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.dto.AgentCitation;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent Run 聚合根：单机内存运行态与状态机。
 * <p>
 * 状态转移：{@code PENDING -> RUNNING -> WAITING_APPROVAL -> ...}。
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
    /**
     * 实际渲染进模型上下文的证据（Phase 2C 三账分离的"最终上下文"账）：
     * 同一 chunk 重复进入时保留内容更完整的一版（精读覆盖检索摘要）。
     */
    private final Map<String, ContextEvidence> contextEvidences = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger evidenceSequence =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile List<AgentCitation> finalCitations = List.of();

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

    /** AgentScope 已写入 ASKING 检查点后，结束当前执行片段并等待用户决定。 */
    public void waitForApproval() {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.WAITING_APPROVAL;
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

    /** 返回本次 Run 还可以产生的工具结果字符数。 */
    public int remainingToolResultCharacters(AgentRunBudget budget) {
        if (budget == null) {
            return 0;
        }
        return Math.max(0, budget.maxToolResultCharacters() - toolResultCharacters);
    }

    /** 记录一次检索命中的完整片段（供离线评测收集检索证据）。 */
    public void addRetrievedHit(KnowledgeHit hit) {
        if (hit != null) {
            this.retrievedHits.add(hit);
        }
    }

    /**
     * 登记一块实际渲染给模型的证据；同 chunkId 已存在且新证据正文不短于旧版时覆盖，
     * 保证引用映射与核验使用最完整的上下文版本。登记顺序由 Run 统一分配。
     */
    public void addContextEvidence(ContextEvidence evidence) {
        if (evidence == null || evidence.chunkId() == null) {
            return;
        }
        ContextEvidence ordered = evidence.order() > 0 ? evidence
                : withOrder(evidence, evidenceSequence.incrementAndGet());
        contextEvidences.merge(ordered.chunkId(), ordered, (existing, incoming) ->
                evidenceLength(incoming) >= evidenceLength(existing) ? incoming : existing);
    }

    private ContextEvidence withOrder(ContextEvidence evidence, int order) {
        return new ContextEvidence(evidence.chunkId(), evidence.fileId(), evidence.filename(),
                evidence.content(), evidence.page(), evidence.sheetName(), evidence.sectionId(),
                evidence.chunkIndex(), evidence.documentVersion(), order);
    }

    private int evidenceLength(ContextEvidence evidence) {
        return evidence.content() == null ? 0 : evidence.content().length();
    }

    /** 记录回答后核验完成的结构化引用（三账分离的"最终引用"账，成功收尾前写入一次）。 */
    public void recordCitations(List<AgentCitation> citations) {
        this.finalCitations = citations == null ? List.of() : List.copyOf(citations);
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

    public boolean isWaitingApproval() {
        return this.status == AgentRunStatus.WAITING_APPROVAL;
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

    /** 上下文证据按首次进入顺序返回（同 chunk 去重后保留最完整版本）。 */
    public List<ContextEvidence> contextEvidences() {
        List<ContextEvidence> ordered = new ArrayList<>(contextEvidences.values());
        ordered.sort(java.util.Comparator.comparingInt(ContextEvidence::order));
        return List.copyOf(ordered);
    }

    public List<AgentCitation> finalCitations() {
        return finalCitations;
    }

    /**
     * 单块上下文证据（Phase 2C）：由检索/精读工具在渲染进模型上下文时登记，
     * 是最终引用的唯一合法来源。
     */
    public record ContextEvidence(
            String chunkId,
            Long fileId,
            String filename,
            String content,
            Integer page,
            String sheetName,
            String sectionId,
            int chunkIndex,
            Integer documentVersion,
            int order) {
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
