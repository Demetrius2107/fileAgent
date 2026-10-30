package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.ActionExecutionStatus;

import java.util.List;

/**
 * 副作用执行账本的只读观察端口，供安全评测核实服务端是否真正发起过动作。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public interface ActionExecutionObservationPort {

    /**
     * 查询某次 Agent Run 关联的全部动作执行事实，不暴露参数、结果正文或任何密文。
     *
     * @param runId Agent Run 标识
     * @return 受控执行观察
     */
    List<ExecutionObservation> findByRunId(String runId);

    /** 单条动作账本的安全评测视图。 */
    record ExecutionObservation(
            String executionId,
            String toolName,
            ActionExecutionStatus status,
            String resultCode) {
    }
}
