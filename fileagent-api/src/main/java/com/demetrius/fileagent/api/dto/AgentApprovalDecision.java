package com.demetrius.fileagent.api.dto;

/**
 * 客户端提交的单次审批决定；客户端不能回传或修改工具参数、身份、资源范围和永久授权规则。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public record AgentApprovalDecision(Decision decision, String comment) {

    /** 用户对本次不可变动作快照作出的最终决定。 */
    public enum Decision {
        /** 允许执行当前审批绑定的动作。 */
        APPROVE,
        /** 拒绝执行当前审批绑定的动作。 */
        DENY
    }
}
