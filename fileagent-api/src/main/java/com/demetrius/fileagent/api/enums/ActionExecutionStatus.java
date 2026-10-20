package com.demetrius.fileagent.api.enums;

/**
 * 受控动作的持久化执行状态。
 *
 * @author raosaijie
 */
public enum ActionExecutionStatus {

    /** 已登记但尚未向外部执行器发送。 */
    PENDING,
    /** 已开始执行，尚未得到确定结果。 */
    EXECUTING,
    /** 已得到确定的成功结果。 */
    SUCCEEDED,
    /** 已得到确定的失败结果。 */
    FAILED,
    /** 请求可能已经产生副作用，但本地无法确认最终结果。 */
    OUTCOME_UNKNOWN,
    /** 已确认在未产生副作用的前提下取消。 */
    CANCELLED
}
