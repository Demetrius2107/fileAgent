package com.demetrius.fileagent.agent.domain.run;

import java.time.Duration;

/**
 * Agent Run 预算：对模型调用、步骤、工具结果长度与总时长施加硬上限。
 * <p>
 * 运行时在每次模型调用与工具调用前检查；超限后不再调用，转入终态。
 */
public record AgentRunBudget(
        int maxSteps,
        int maxModelCalls,
        int singleToolResultCharacters,
        int maxToolResultCharacters,
        Duration runTimeout) {

    public boolean stepsExceeded(int currentSteps) {
        return currentSteps >= maxSteps;
    }

    public boolean modelCallsExceeded(int currentCalls) {
        return currentCalls >= maxModelCalls;
    }

    public boolean timedOut(java.time.Instant startedAt, java.time.Instant now) {
        return startedAt != null && Duration.between(startedAt, now).compareTo(runTimeout) >= 0;
    }
}
