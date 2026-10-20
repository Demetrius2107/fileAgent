package com.demetrius.fileagent.api.enums;

/**
 * 动作取消的保守结果，调用方不得把未知结果当成取消成功。
 *
 * @author raosaijie
 */
public enum ActionCancelStatus {

    /** 请求尚未发送，因此确定没有产生外部副作用。 */
    NOT_STARTED,
    /** 执行器确认请求已取消且没有产生外部副作用。 */
    CANCELLED,
    /** 请求可能已经送达外部系统，结果必须人工核对。 */
    OUTCOME_UNKNOWN
}
