package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepType;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 将 Agent 最终回答、审计步骤和 Run 成功状态作为一个事务提交。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@Service
@RequiredArgsConstructor
public class AgentRunFinalizationService {

    private static final String FINALIZATION_SUCCESS_CODE = "FINALIZED";
    private static final String FINALIZATION_SUMMARY = "回答已保存";

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final SessionMessagePort sessionMessagePort;
    private final List<AgentFinalizationContributor> contributors;

    /**
     * 提交 Run 成功结果；任何 contributor 或消息写入失败都会回滚本次最终化。
     */
    @Transactional
    public AgentRunEntity finalizeSuccess(String runId, String answer, List<String> sourceFiles) {
        AgentRunEntity run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new BizException(404, "Agent Run 不存在: " + runId));
        if (run.getStatus() != AgentRunStatus.RUNNING) {
            return run;
        }

        List<String> safeSources = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        for (AgentFinalizationContributor contributor : contributors) {
            contributor.contribute(run, answer, safeSources);
        }

        Long messageId = sessionMessagePort.append(run.getSessionId(), MessageType.ASSISTANT, answer);
        Instant now = Instant.now();
        AgentStepEntity step = AgentStepEntity.pending(runId,
                stepRepository.findStepsByRunId(runId).size() + 1,
                AgentStepType.FINALIZATION, now);
        step.start(now);
        step.succeed(FINALIZATION_SUCCESS_CODE, FINALIZATION_SUMMARY, now);
        stepRepository.save(step);

        run.succeed(messageId, now);
        return runRepository.save(run);
    }
}
