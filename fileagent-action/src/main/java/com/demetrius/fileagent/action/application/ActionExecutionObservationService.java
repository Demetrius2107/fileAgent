package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.api.port.ActionExecutionObservationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 副作用账本的最小只读投影：评测只需核实是否执行，不能读取代码、参数或结果正文。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionExecutionObservationService implements ActionExecutionObservationPort {

    private final ActionToolExecutionRepository executionRepository;

    @Override
    public List<ExecutionObservation> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return executionRepository.findByRunId(runId).stream()
                .map(execution -> new ExecutionObservation(
                        execution.getExecutionId(), execution.getToolName(), execution.getStatus(),
                        execution.getResultCode()))
                .toList();
    }
}
