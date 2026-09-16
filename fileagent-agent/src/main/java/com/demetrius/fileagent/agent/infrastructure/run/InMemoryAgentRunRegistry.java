package com.demetrius.fileagent.agent.infrastructure.run;

import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机内存 Agent Run 注册表。
 * <p>
 * 完成/终态的记录保留 {@code completedRunTtl} 后由 {@link #evictExpired()} 清理；
 * 运行中的记录不因 TTL 被清理。进程重启后记录自然丢失（Phase 1 显式限制）。
 */
@Component
public class InMemoryAgentRunRegistry {

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Runnable> cancelHandles = new ConcurrentHashMap<>();
    private final Clock clock;
    private final java.time.Duration completedRunTtl;

    public InMemoryAgentRunRegistry(AgentProperties properties, Clock clock) {
        this.clock = clock;
        this.completedRunTtl = properties.getCompletedRunTtl();
    }

    public void save(AgentRun run) {
        runs.put(run.runId(), run);
    }

    public Optional<AgentRun> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public void registerCancelHandle(String runId, Runnable handle) {
        cancelHandles.put(runId, handle);
    }

    public Optional<Runnable> cancelHandle(String runId) {
        return Optional.ofNullable(cancelHandles.get(runId));
    }

    public void remove(String runId) {
        runs.remove(runId);
        cancelHandles.remove(runId);
    }

    /** 清理已过 TTL 的终态记录，运行中的记录始终保留。 */
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(completedRunTtl);
        runs.values().removeIf(run -> run.isTerminal()
                && run.endedAt() != null
                && run.endedAt().isBefore(cutoff));
    }
}
