package com.demetrius.fileagent.agent.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Agent 运行时基础设施配置。
 */
@Configuration
public class AgentRuntimeConfig {

    /** 供 Run 注册表做 TTL 判断的系统时钟。 */
    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }
}
