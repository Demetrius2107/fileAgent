package com.demetrius.fileagent.agent.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 运行时配置（前缀 {@code fileagent.agent}）。
 * <p>
 * 默认关闭（enabled=false），需显式开启后 Agent 入口才可用。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.agent")
public class AgentProperties {

    /** Feature Flag：是否启用 Agent 入口，默认关闭。 */
    private boolean enabled = false;

    private int maxSteps = 4;
    private int maxModelCalls = 4;
    private Duration runTimeout = Duration.ofSeconds(45);
    private Duration toolTimeout = Duration.ofSeconds(5);
    private int maxOutputTokens = 2048;
    private int singleToolResultCharacters = 4000;
    private int maxToolResultCharacters = 12000;
    private Duration completedRunTtl = Duration.ofMinutes(15);
}
