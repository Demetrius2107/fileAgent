package com.demetrius.fileagent.api.dto.action;

import com.demetrius.fileagent.api.enums.ActionExecutionStatus;

import java.time.Instant;

/**
 * 动作执行的受控视图，不跨域暴露请求正文、密文或持久化实体。
 *
 * @author raosaijie
 */
public record ActionExecutionView(
        String executionId,
        String operationId,
        ActionExecutionStatus status,
        String resultCode,
        String resultSummary,
        boolean recoverable,
        Instant startedAt,
        Instant endedAt) {
}
