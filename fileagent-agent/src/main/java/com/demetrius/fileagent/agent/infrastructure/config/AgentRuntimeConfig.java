package com.demetrius.fileagent.agent.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;

/**
 * Agent 运行时基础设施配置。
 */
@Configuration
@EnableScheduling
public class AgentRuntimeConfig {

    /** 供 Run 注册表做 TTL 判断的系统时钟。 */
    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }

    /** 有界后台执行池：线程数限制并行模型调用，队列容量与 Coordinator 许可保持一致。 */
    @Bean("agentRunTaskExecutor")
    ThreadPoolTaskExecutor agentRunTaskExecutor(AgentProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getMaxConcurrentRuns());
        executor.setMaxPoolSize(properties.getMaxConcurrentRuns());
        executor.setQueueCapacity(properties.getMaxQueuedRuns());
        executor.setThreadNamePrefix("agent-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
