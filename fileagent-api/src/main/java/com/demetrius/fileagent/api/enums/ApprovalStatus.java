package com.demetrius.fileagent.api.enums;

/**
 * Agent 操作审批的生命周期；只有 PENDING 状态允许作出一次最终决定。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public enum ApprovalStatus {

    /** 服务端已生成不可变动作快照，正在等待用户作出决定。 */
    PENDING,
    /** 用户已批准本次快照绑定的动作，可进入恢复准备。 */
    APPROVED,
    /** 用户已拒绝本次动作，Agent 将收到固定拒绝结果。 */
    DENIED,
    /** 用户未在截止时间前决定，不能再恢复该动作。 */
    EXPIRED,
    /** Run 被取消或动作绑定已失效，审批不再可决定。 */
    CANCELLED
}
