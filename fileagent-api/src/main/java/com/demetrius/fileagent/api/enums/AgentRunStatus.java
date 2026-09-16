package com.demetrius.fileagent.api.enums;

/**
 * Agent Run 生命周期状态。
 * <p>
 * Phase 1 只进入 {@code PENDING/RUNNING} 与四个终态；{@code WAITING_USER_INPUT} 与
 * {@code WAITING_APPROVAL} 作为 API 预留，保证后续 HITL 的接口兼容性。
 */
public enum AgentRunStatus {
    PENDING,
    RUNNING,
    WAITING_USER_INPUT,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
