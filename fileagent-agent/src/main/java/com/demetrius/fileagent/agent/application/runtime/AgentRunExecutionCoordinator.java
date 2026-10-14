package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.application.AgentRunFinalizationService;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentCitation;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * Agent Run 的后台执行协调器：统一控制有界容量、执行租约和进程重启后的安全收敛。
 * <p>
 * Run 的业务状态始终以数据库为准；本类内存中只保留当前进程的容量许可和正在持有的租约。
 * 浏览器 SSE 订阅不经过这里占用许可。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Slf4j
@Component
public class AgentRunExecutionCoordinator {

    private static final String CODE_RUN_INTERRUPTED = "AGENT_RUN_INTERRUPTED";
    private static final String CODE_RECOVERY_FAILED = "AGENT_RECOVERY_FAILED";

    private final AgentRunRepository runRepository;
    private final AgentExecutionEngine executionEngine;
    private final AgentRunCommandCodec commandCodec;
    private final AgentRunEventHub eventHub;
    private final AgentRunFinalizationService finalizationService;
    private final AgentProperties properties;
    private final Clock clock;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore capacity;
    private final Set<String> reservedRunIds = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledRunIds = ConcurrentHashMap.newKeySet();
    private final Set<String> leasedRunIds = ConcurrentHashMap.newKeySet();
    private final String executionOwner = "fileagent-" + UUID.randomUUID();

    public AgentRunExecutionCoordinator(AgentRunRepository runRepository,
                                        AgentExecutionEngine executionEngine,
                                        AgentRunCommandCodec commandCodec,
                                        AgentRunEventHub eventHub,
                                        AgentRunFinalizationService finalizationService,
                                        AgentProperties properties,
                                        Clock clock,
                                        @Qualifier("agentRunTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.runRepository = runRepository;
        this.executionEngine = executionEngine;
        this.commandCodec = commandCodec;
        this.eventHub = eventHub;
        this.finalizationService = finalizationService;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.capacity = new Semaphore(Math.addExact(properties.getMaxConcurrentRuns(), properties.getMaxQueuedRuns()), true);
    }

    /**
     * 启动期先收敛旧 Run，再开放新请求的容量预留。
     */
    @PostConstruct
    public void initialize() {
        recoverNow();
    }

    /**
     * 每轮先续租本实例正在执行的 Run，再处理数据库中可恢复或应安全失败的记录。
     */
    @Scheduled(fixedDelayString = "${fileagent.agent.recovery-scan-interval:15s}")
    public void recoverNow() {
        Instant now = clock.instant();
        renewHeldLeases(now);
        for (AgentRunEntity run : runRepository.findRecoverable(now)) {
            if (run.getStatus() == AgentRunStatus.PENDING) {
                recoverPending(run, now);
            } else if (run.getStatus() == AgentRunStatus.RUNNING) {
                failRecovery(run, CODE_RUN_INTERRUPTED, now);
            }
        }
    }

    /**
     * 原子预留运行容量。调用方在 PENDING Run 事务失败或入队失败时必须释放。
     */
    public boolean tryReserveCapacity(String runId) {
        requireRunId(runId);
        if (reservedRunIds.contains(runId)) {
            return true;
        }
        if (!capacity.tryAcquire()) {
            return false;
        }
        if (reservedRunIds.add(runId)) {
            return true;
        }
        capacity.release();
        return true;
    }

    /**
     * 释放排队或运行中的容量许可；重复调用安全无副作用。
     */
    public void releaseCapacity(String runId) {
        if (runId == null) {
            return;
        }
        scheduledRunIds.remove(runId);
        leasedRunIds.remove(runId);
        if (reservedRunIds.remove(runId)) {
            capacity.release();
        }
    }

    /**
     * 将已持有容量许可的可信命令放入有界后台线程池。
     */
    public void enqueue(AgentRunCommand command) {
        requireCommand(command);
        String runId = command.runId();
        if (!reservedRunIds.contains(runId)) {
            throw new IllegalStateException("Agent Run 未预留执行容量: " + runId);
        }
        if (!scheduledRunIds.add(runId)) {
            return;
        }
        try {
            executor.execute(() -> executeInWorker(command));
        } catch (RuntimeException e) {
            scheduledRunIds.remove(runId);
            failExecution(runId, e);
            releaseCapacity(runId);
            throw e;
        }
    }

    private void recoverPending(AgentRunEntity run, Instant now) {
        if (isPendingExpired(run, now)) {
            failRecovery(run, CODE_RUN_INTERRUPTED, now);
            return;
        }
        if (!tryReserveCapacity(run.getRunId())) {
            failRecovery(run, CODE_RUN_INTERRUPTED, now);
            return;
        }
        try {
            enqueue(commandCodec.decrypt(run));
        } catch (Exception e) {
            log.warn("恢复 PENDING Agent Run 失败 runId={}: {}", run.getRunId(), e.getMessage());
            releaseCapacity(run.getRunId());
            failRecovery(run, CODE_RECOVERY_FAILED, now);
        }
    }

    private void executeInWorker(AgentRunCommand command) {
        String runId = command.runId();
        try {
            AgentRunEntity run = runRepository.findByRunId(runId).orElse(null);
            if (run == null || run.getStatus() != AgentRunStatus.PENDING) {
                releaseCapacity(runId);
                return;
            }
            Instant now = clock.instant();
            if (!runRepository.acquireLease(runId, run.getVersion(), executionOwner, now,
                    now.plus(properties.getLeaseDuration()))) {
                releaseCapacity(runId);
                return;
            }
            AgentRunEntity leasedRun = runRepository.findByRunId(runId).orElse(null);
            if (leasedRun == null || leasedRun.getStatus() != AgentRunStatus.PENDING) {
                releaseCapacity(runId);
                return;
            }
            leasedRun.start(now);
            AgentRunEntity runningRun = runRepository.save(leasedRun);
            leasedRunIds.add(runId);
            Flux<AgentRunEvent> execution = executionEngine.execute(command);
            StringBuilder answer = new StringBuilder();
            LinkedHashSet<String> sourceFiles = new LinkedHashSet<>();
            List<AgentCitation> citations = new CopyOnWriteArrayList<>();
            execution
                    .doOnNext(event -> handleExecutionEvent(runId, event, answer, sourceFiles, citations))
                    .doOnError(error -> failExecution(runId, error))
                    .doFinally(signal -> releaseCapacity(runId))
                    .subscribe();
        } catch (Exception e) {
            failExecution(runId, e);
            releaseCapacity(runId);
        }
    }

    private void renewHeldLeases(Instant now) {
        for (String runId : leasedRunIds) {
            if (!runRepository.renewLease(runId, executionOwner, now, now.plus(properties.getLeaseDuration()))) {
                log.warn("Agent Run 执行租约续期失败 runId={}", runId);
                releaseCapacity(runId);
            }
        }
    }

    private void handleExecutionEvent(String runId, AgentRunEvent event, StringBuilder answer,
                                      LinkedHashSet<String> sourceFiles, List<AgentCitation> citations) {
        AgentRunEntity run = runRepository.findByRunId(runId).orElse(null);
        if (run == null || isTerminal(run.getStatus())) {
            return;
        }
        if ("message.delta".equals(event.type())) {
            answer.append(event.content() == null ? "" : event.content());
        }
        if ("sources".equals(event.type()) && event.files() != null) {
            sourceFiles.addAll(event.files());
        }
        if ("sources".equals(event.type()) && event.citations() != null) {
            citations.addAll(event.citations());
        }
        if ("run.completed".equals(event.type())) {
            AgentRunEntity finalized = finalizationService.finalizeSuccess(runId, answer.toString(),
                    List.copyOf(sourceFiles), List.copyOf(citations));
            if (finalized.getStatus() == AgentRunStatus.SUCCEEDED) {
                eventHub.publish(AgentRunEvent.completed(runId, finalized.getAssistantMessageId()),
                        Math.max(0L, finalized.getVersion()));
            }
            return;
        }
        if ("run.failed".equals(event.type())) {
            String code = event.code() == null || event.code().isBlank()
                    ? CODE_RUN_INTERRUPTED : event.code();
            run.fail(code, clock.instant());
            AgentRunEntity failed = runRepository.save(run);
            eventHub.publish(AgentRunEvent.failed(runId, code,
                    event.message() == null ? "Agent 运行中断" : event.message(), run.getTraceId()),
                    Math.max(0L, failed.getVersion()));
            return;
        }
        eventHub.publish(event, Math.max(0L, run.getVersion()));
    }

    private void failExecution(String runId, Throwable error) {
        log.warn("Agent Run 后台执行失败 runId={}: {}", runId, error.getMessage());
        runRepository.findByRunId(runId).ifPresent(run -> {
            if (run.getStatus() == AgentRunStatus.PENDING || run.getStatus() == AgentRunStatus.RUNNING) {
                run.fail(CODE_RUN_INTERRUPTED, clock.instant());
                AgentRunEntity failed = runRepository.save(run);
                eventHub.publish(AgentRunEvent.failed(runId, CODE_RUN_INTERRUPTED,
                        "Agent 运行中断", run.getTraceId()), Math.max(0L, failed.getVersion()));
            }
        });
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.SUCCEEDED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED
                || status == AgentRunStatus.TIMED_OUT;
    }

    private void failRecovery(AgentRunEntity run, String code, Instant now) {
        if (run.getStatus() == AgentRunStatus.PENDING || run.getStatus() == AgentRunStatus.RUNNING) {
            run.fail(code, now);
            AgentRunEntity failed = runRepository.save(run);
            eventHub.publish(AgentRunEvent.failed(run.getRunId(), code,
                    "Agent 运行未能安全恢复", run.getTraceId()), Math.max(0L, failed.getVersion()));
        }
        releaseCapacity(run.getRunId());
    }

    private boolean isPendingExpired(AgentRunEntity run, Instant now) {
        return run.getCreatedAt().plus(properties.getPendingStartTimeout()).isBefore(now);
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
    }

    private static void requireCommand(AgentRunCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Agent Run 命令不能为空");
        }
        requireRunId(command.runId());
    }
}
