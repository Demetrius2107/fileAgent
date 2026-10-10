package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;

import java.util.List;

/**
 * Agent 回答最终化的可选扩展点。
 * <p>
 * Phase 3A 默认没有实现；Phase 2C 合入后可在最终提交前补充引用或其他受控事实，且必须与
 * 助手消息、FINALIZATION Step 和 Run 成功状态处于同一事务。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
public interface AgentFinalizationContributor {

    /**
     * 为已生成的回答补充最终化事实。
     *
     * @param run 当前仍处于 RUNNING 的持久化 Run
     * @param answer 已生成的完整回答
     * @param sourceFiles 本次回答声明的来源文件
     */
    void contribute(AgentRunEntity run, String answer, List<String> sourceFiles);
}
