package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;

import java.time.Instant;
import java.util.Collections;
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

    private final String runId;
    private final Long sessionId;
    private final String traceId;

    private AgentRunStatus status = AgentRunStatus.PENDING;
    private Instant startedAt;
    private Instant endedAt;
    private int stepCount;
    private int modelCallCount;
    private final Set<String> allowedChunkIds = ConcurrentHashMap.newKeySet();
    private final List<KnowledgeHit> retrievedHits = new CopyOnWriteArrayList<>();
    private volatile boolean cancelRequested;
    private Long assistantMessageId;
    private String failureCode;
    private volatile String pendingToolFailureCode;
    private volatile int lastToolResultCount;

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

    public void addAllowedChunk(String chunkId) {
        if (chunkId != null) {
            this.allowedChunkIds.add(chunkId);
        }
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
}
