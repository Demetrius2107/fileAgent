package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.List;

/**
 * 基于持久化 Run 的查询、事件订阅与显式取消服务。
 * <p>
 * 数据库是状态事实来源，事件通道仅用于本进程内的有限回放；取消必须先提交终态，再中断当前
 * 进程的 AgentScope 句柄，避免浏览器断连直接影响后台执行。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@Service
@RequiredArgsConstructor
public class PersistentAgentRuntimeService implements AgentRuntimePort {

    private final AgentRunRepository runRepository;
    private final AgentRunEventHub eventHub;
    private final AgentExecutionHandleRegistry executionHandleRegistry;
    private final AgentRunExecutionCoordinator executionCoordinator;
    private final Clock clock;

    @Override
    public AgentRunSnapshot snapshot(String runId) {
        return toSnapshot(requireRun(runId));
    }

    @Override
    public AgentRunSnapshot active(Long sessionId) {
        if (sessionId == null) {
            throw new BizException("sessionId 不能为空");
        }
        return runRepository.findActiveBySessionId(sessionId)
                .map(this::toSnapshot)
                .orElse(null);
    }

    @Override
    public Flux<AgentRunEvent> events(String runId, String lastEventId) {
        AgentRunEntity run = requireRun(runId);
        return eventHub.subscribe(run.getRunId(), lastEventId, run.getStatus(), Math.max(0L, run.getVersion()));
    }

    @Override
    @Transactional
    public AgentRunSnapshot cancel(String runId) {
        AgentRunEntity run = requireRun(runId);
        if (run.getStatus() != AgentRunStatus.PENDING && run.getStatus() != AgentRunStatus.RUNNING) {
            return toSnapshot(run);
        }

        AgentRunStatus previousStatus = run.getStatus();
        run.cancel(clock.instant());
        AgentRunEntity cancelled = runRepository.save(run);
        afterCommit(() -> {
            eventHub.publish(AgentRunEvent.status(cancelled.getRunId(), AgentRunStatus.CANCELLED, "运行已取消"),
                    Math.max(0L, cancelled.getVersion()));
            boolean interrupted = executionHandleRegistry.interrupt(cancelled.getRunId());
            if (previousStatus == AgentRunStatus.PENDING || !interrupted) {
                executionCoordinator.releaseCapacity(cancelled.getRunId());
            }
        });
        return toSnapshot(cancelled);
    }

    private AgentRunEntity requireRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new BizException("runId 不能为空");
        }
        return runRepository.findByRunId(runId)
                .orElseThrow(() -> new BizException(404, "Agent Run 不存在: " + runId));
    }

    private AgentRunSnapshot toSnapshot(AgentRunEntity run) {
        return new AgentRunSnapshot(
                run.getRunId(),
                run.getSessionId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getEndedAt(),
                run.getStepCount(),
                run.getModelCallCount(),
                run.getAssistantMessageId(),
                run.getFailureCode(),
                run.getTraceId(),
                run.getHistoryCharacters(),
                run.getSummaryCharacters(),
                run.getSearchSnippetCharacters(),
                run.getDocumentReadCharacters(),
                run.getToolResultCharacters(),
                run.getInputTokens(),
                run.getOutputTokens(),
                run.getTotalTokens(),
                false,
                List.of(),
                run.getVersion());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
