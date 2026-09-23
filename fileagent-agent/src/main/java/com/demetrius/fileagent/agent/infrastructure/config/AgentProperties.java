package com.demetrius.fileagent.agent.infrastructure.config;

import com.demetrius.fileagent.common.exception.BizException;
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

    /** Feature Flag：是否启用结构化检索模式（search_docs 声明查询类型 + 档位策略），默认关闭。 */
    private boolean adaptiveRetrievalEnabled = false;

    private int maxSteps = 8;
    private int maxModelCalls = 4;
    private Duration runTimeout = Duration.ofSeconds(45);
    private Duration toolTimeout = Duration.ofSeconds(5);
    private int maxOutputTokens = 2048;
    private int singleToolResultCharacters = 4000;
    private int maxToolResultCharacters = 12000;
    private Duration completedRunTtl = Duration.ofMinutes(15);

    private int maxPromptCharacters = 8000;
    private int maxHistoryCharacters = 8000;
    private int maxSummaryCharacters = 2000;
    private int maxRecentHistoryCharacters = 6000;
    private int searchSnippetCharacters = 500;
    private int outlineMaxEntries = 50;
    private int readMaxChunks = 3;
    private int maxTotalTokens = 60000;

    /** 启动期校验上下文分项预算关系。 */
    public void validate() {
        if (maxSteps < 1 || maxModelCalls < 1 || maxOutputTokens < 1
                || singleToolResultCharacters < 1 || maxToolResultCharacters < 1
                || maxPromptCharacters < 1 || maxHistoryCharacters < 1
                || maxSummaryCharacters < 1 || maxRecentHistoryCharacters < 1
                || searchSnippetCharacters < 1 || outlineMaxEntries < 1
                || readMaxChunks < 1 || maxTotalTokens < 1) {
            throw new BizException("fileagent.agent 上下文预算必须为正数");
        }
        if (maxSummaryCharacters + maxRecentHistoryCharacters > maxHistoryCharacters) {
            throw new BizException("fileagent.agent 摘要预算与最近历史预算不能超过历史总预算");
        }
        if (singleToolResultCharacters > maxToolResultCharacters) {
            throw new BizException("fileagent.agent 单次工具结果预算不能超过累计工具结果预算");
        }
    }
}
