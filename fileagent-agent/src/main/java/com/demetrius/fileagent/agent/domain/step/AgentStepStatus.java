package com.demetrius.fileagent.agent.domain.step;

/**
 * Agent Step 生命周期状态；审批等待与 Run 状态独立记录，便于审计单个工具步骤。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public enum AgentStepStatus {

    /** 已创建但尚未被后台执行器领取。 */
    PENDING,
    /** 正在执行，执行器崩溃后需结合 Run 和账本收敛。 */
    RUNNING,
    /** 工具调用已暂停，等待用户审批。 */
    WAITING_APPROVAL,
    /** 已得到确定的成功结果。 */
    SUCCEEDED,
    /** 已得到确定的失败结果。 */
    FAILED,
    /** 被用户取消或因 Run 取消而终止。 */
    CANCELLED,
    /** 等待审批超时，不能继续执行。 */
    EXPIRED
}
