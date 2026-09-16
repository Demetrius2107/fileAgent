package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.AgentRunStatus;

import java.time.Instant;

/**
 * Agent Run 查询快照（GET 接口返回）。
 * <p>
 * 不返回思维链、工具正文、模型原始响应或内部异常堆栈。
 */
public record AgentRunSnapshot(
        String runId,
        Long sessionId,
        AgentRunStatus status,
        Instant startedAt,
        Instant endedAt,
        int stepCount,
        int modelCallCount,
        Long assistantMessageId,
        String failureCode,
        String traceId) {
}
