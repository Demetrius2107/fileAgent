package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunCommandCodec;
import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.agent.application.runtime.AgentRunExecutionCoordinator;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审批决定到后台续跑的编排：先原子决策，首次决议后在提交前预留容量，提交后才发布事件和入队。
 *
 * @author raosaijie
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApprovalWorkflowService {

    private static final String LOCAL_OPERATOR = "local-operator";
    private static final String CODE_CAPACITY_EXCEEDED = "AGENT_CAPACITY_EXCEEDED";

    private final AgentApprovalService approvalService;
    private final AgentRunExecutionCoordinator executionCoordinator;
    private final AgentRunCommandCodec commandCodec;
    private final AgentRunEventHub eventHub;

    /**
     * 处理一次用户决定。首次决议成功后才预留容量；容量不足会回滚同一事务中的 CAS，保持 Approval=PENDING。
     */
    @Transactional
    public AgentApprovalView decide(String runId, String approvalId, AgentApprovalDecision request) {
        AgentApprovalService.DecisionOutcome outcome = approvalService.decide(
                runId, approvalId, request, LOCAL_OPERATOR);
        if (!outcome.requeued()) {
            return outcome.approval();
        }
        if (!executionCoordinator.tryReserveCapacity(runId)) {
            throw new BizException(429, CODE_CAPACITY_EXCEEDED + ": Agent 运行队列已满");
        }
        try {
            AgentRunCommand command = commandCodec.decrypt(outcome.run());
            registerResumeAfterCommit(command, outcome);
            return outcome.approval();
        } catch (RuntimeException exception) {
            executionCoordinator.releaseCapacity(runId);
            throw exception;
        }
    }

    private void registerResumeAfterCommit(AgentRunCommand command, AgentApprovalService.DecisionOutcome outcome) {
        Runnable resume = () -> {
            if (outcome.requeued()) {
                eventHub.publish(AgentRunEvent.approvalResolved(command.runId(), outcome.approval()),
                        Math.max(0L, outcome.run().getVersion()));
            }
            try {
                executionCoordinator.enqueue(command);
            } catch (RuntimeException exception) {
                log.warn("审批后的 Agent Run 入队失败 runId={}: {}", command.runId(), exception.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            resume.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resume.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    executionCoordinator.releaseCapacity(command.runId());
                }
            }
        });
    }
}
