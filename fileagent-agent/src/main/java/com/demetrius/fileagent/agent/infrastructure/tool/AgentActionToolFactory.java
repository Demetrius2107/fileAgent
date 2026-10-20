package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.api.dto.AgentRunCommand;

/**
 * 按 Agent Run 创建副作用工具的工厂契约，禁止将带有运行级可信绑定的工具注册为单例 Bean。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
@FunctionalInterface
public interface AgentActionToolFactory {

    /**
     * 为当前可信 Run 命令创建独立工具实例。
     *
     * @param command 已持久化并校验过的 Run 命令
     * @return 仅能服务当前 Run 的副作用工具
     */
    RunScopedActionTool create(AgentRunCommand command);
}
