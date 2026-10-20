package com.demetrius.fileagent.api.dto.action;

import com.demetrius.fileagent.api.enums.ActionCancelStatus;

/**
 * 动作取消结果；结果未知时调用方必须停止自动执行并转人工核对。
 *
 * @author raosaijie
 */
public record ActionCancelResult(String executionId, ActionCancelStatus status) {
}
