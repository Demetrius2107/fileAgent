package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.application.AgentRunFinalizationService;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.persistence.AgentPersistenceAdapter;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用同一 H2 库重建 Spring Context，验证 Agent Run 在进程重启后的恢复矩阵。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
class AgentRuntimeRestartIntegrationTest {

    private static final AtomicReference<AgentRunCommand> RECOVERY_COMMAND = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> EXECUTION_LATCH = new AtomicReference<>();
    private static final AtomicInteger EXECUTION_COUNT = new AtomicInteger();

    @AfterEach
    void resetRecoveryFixtures() {
        RECOVERY_COMMAND.set(null);
        EXECUTION_LATCH.set(null);
        EXECUTION_COUNT.set(0);
    }

    @Test
    void shouldRequeuePendingRunAfterContextRestart() throws Exception {
        String databaseName = databaseName("pending");
        ConfigurableApplicationContext firstContext = startContext(databaseName);
        String runId = "restart-pending";
        try {
            firstContext.getBean(AgentRunRepository.class).save(pending(runId, 101L));
        } finally {
            firstContext.close();
        }

        CountDownLatch executionLatch = new CountDownLatch(1);
        EXECUTION_LATCH.set(executionLatch);
        RECOVERY_COMMAND.set(command(runId, 101L));
        ConfigurableApplicationContext secondContext = startContext(databaseName);
        try {
            assertThat(executionLatch.await(3, TimeUnit.SECONDS)).isTrue();
            AgentRunEntity recovered = secondContext.getBean(AgentRunRepository.class)
                    .findByRunId(runId).orElseThrow();
            assertThat(recovered.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(recovered.getExecutionOwner()).isNotBlank();
        } finally {
            secondContext.close();
        }
    }

    @Test
    void shouldFailStaleRunningRunWithoutReplayingItAfterContextRestart() {
        String databaseName = databaseName("stale");
        ConfigurableApplicationContext firstContext = startContext(databaseName);
        String runId = "restart-stale";
        try {
            AgentRunEntity stale = pending(runId, 102L);
            stale.start(Instant.now().minusSeconds(60));
            firstContext.getBean(AgentRunRepository.class).save(stale);
        } finally {
            firstContext.close();
        }

        ConfigurableApplicationContext secondContext = startContext(databaseName);
        try {
            AgentRunEntity recovered = secondContext.getBean(AgentRunRepository.class)
                    .findByRunId(runId).orElseThrow();
            assertThat(recovered.getStatus()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(recovered.getFailureCode()).isEqualTo("AGENT_RUN_INTERRUPTED");
            assertThat(recovered.getActiveSlot()).isNull();
            assertThat(EXECUTION_COUNT).hasValue(0);
        } finally {
            secondContext.close();
        }
    }

    private ConfigurableApplicationContext startContext(String databaseName) {
        return new SpringApplicationBuilder(RestartTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=update",
                        "spring.jpa.open-in-view=false",
                        "spring.main.banner-mode=off",
                        "logging.level.org.springframework=WARN",
                        "logging.level.org.hibernate=WARN")
                .run();
    }

    private String databaseName(String suffix) {
        return "agent_restart_" + suffix + '_' + UUID.randomUUID().toString().replace("-", "");
    }

    private AgentRunEntity pending(String runId, Long sessionId) {
        return AgentRunEntity.pending(runId, sessionId, "trace-" + runId,
                "local-operator", "cipher-" + runId, "hmac-" + runId);
    }

    private AgentRunCommand command(String runId, Long sessionId) {
        return new AgentRunCommand(runId, sessionId, "trace-" + runId,
                "恢复执行", List.of(), KnowledgeScope.global());
    }

    /**
     * 最小化运行上下文：真实 JPA 仓储搭配可控执行引擎，避免接触模型或外部知识库。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.demetrius.fileagent.agent")
    @Import(AgentPersistenceAdapter.class)
    static class RestartTestConfiguration {

        @Bean
        AgentProperties agentProperties() {
            AgentProperties properties = new AgentProperties();
            properties.setMaxConcurrentRuns(1);
            properties.setMaxQueuedRuns(1);
            return properties;
        }

        @Bean
        Clock agentClock() {
            return Clock.systemUTC();
        }

        @Bean
        AgentRunEventHub agentRunEventHub(AgentProperties properties) {
            return new AgentRunEventHub(properties);
        }

        @Bean
        AgentRunCommandCodec agentRunCommandCodec() {
            AgentRunCommandCodec codec = mock(AgentRunCommandCodec.class);
            when(codec.decrypt(any(AgentRunEntity.class))).thenAnswer(invocation -> RECOVERY_COMMAND.get());
            return codec;
        }

        @Bean
        AgentExecutionEngine agentExecutionEngine() {
            return command -> Flux.defer(() -> {
                EXECUTION_COUNT.incrementAndGet();
                CountDownLatch latch = EXECUTION_LATCH.get();
                if (latch != null) {
                    latch.countDown();
                }
                return Flux.never();
            });
        }

        @Bean
        AgentRunFinalizationService agentRunFinalizationService() {
            return mock(AgentRunFinalizationService.class);
        }

        @Bean("agentRunTaskExecutor")
        ThreadPoolTaskExecutor agentRunTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setThreadNamePrefix("restart-agent-");
            return executor;
        }

        @Bean
        AgentRunExecutionCoordinator agentRunExecutionCoordinator(
                AgentRunRepository runRepository,
                AgentExecutionEngine executionEngine,
                AgentRunCommandCodec commandCodec,
                AgentRunEventHub eventHub,
                AgentRunFinalizationService finalizationService,
                AgentProperties properties,
                Clock clock,
                ThreadPoolTaskExecutor executor) {
            return new AgentRunExecutionCoordinator(runRepository, executionEngine, commandCodec,
                    eventHub, finalizationService, properties, clock, executor);
        }
    }
}
