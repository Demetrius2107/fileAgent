package com.demetrius.fileagent.agent.infrastructure.run;

import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRunRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryAgentRunRegistry registry() {
        AgentProperties properties = new AgentProperties();
        properties.setCompletedRunTtl(Duration.ofMinutes(15));
        return new InMemoryAgentRunRegistry(properties, CLOCK);
    }

    @Test
    void shouldEvictCompletedRunAfterTtlButKeepRunningRun() {
        InMemoryAgentRunRegistry registry = registry();

        AgentRun completed = AgentRun.pending("completed", 1L, "trace-1");
        completed.start(CLOCK.instant().minus(Duration.ofMinutes(16)));
        completed.succeed(CLOCK.instant().minus(Duration.ofMinutes(16)));
        registry.save(completed);

        AgentRun running = AgentRun.pending("running", 2L, "trace-1");
        running.start(CLOCK.instant().minus(Duration.ofHours(1)));
        registry.save(running);

        registry.evictExpired();

        assertThat(registry.find("completed")).isEmpty();
        assertThat(registry.find("running")).isPresent();
    }

    @Test
    void shouldKeepRecentlyCompletedRun() {
        InMemoryAgentRunRegistry registry = registry();

        AgentRun completed = AgentRun.pending("recent", 1L, "trace-1");
        completed.start(CLOCK.instant().minus(Duration.ofMinutes(1)));
        completed.succeed(CLOCK.instant().minus(Duration.ofMinutes(1)));
        registry.save(completed);

        registry.evictExpired();

        assertThat(registry.find("recent")).isPresent();
    }

    @Test
    void shouldRegisterAndRetrieveCancelHandle() {
        InMemoryAgentRunRegistry registry = registry();
        Runnable handle = () -> {
        };
        registry.registerCancelHandle("run-1", handle);

        assertThat(registry.cancelHandle("run-1")).contains(handle);
    }
}
