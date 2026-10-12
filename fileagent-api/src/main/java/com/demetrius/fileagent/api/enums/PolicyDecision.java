package com.demetrius.fileagent.api.enums;

/**
 * 服务端动作策略的确定性结果，不允许由模型文本直接指定。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public enum PolicyDecision {

    /** 已通过服务端资源、参数和风险校验，可直接执行。 */
    ALLOW,
    /** 不符合安全边界或资源授权，必须拒绝执行。 */
    DENY,
    /** 参数已通过基础校验，但本次操作必须等待用户确认。 */
    ASK
}
