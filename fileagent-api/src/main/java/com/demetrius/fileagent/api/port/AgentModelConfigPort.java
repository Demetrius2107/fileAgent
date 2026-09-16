package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.AgentModelConfig;

/**
 * Agent 模型连接配置端口（由 fileagent-chat 的 infrastructure 实现）。
 * <p>
 * Agent 域构造 AgentScope 模型实例时读取本端口，复用 chat 域已有的启用配置
 * （数据库 active 配置优先，缺失时回落 application.yml + 环境变量默认模型）。
 * 它不返回 Spring AI 的 {@code ChatModel}，因为 AgentScope 使用自己的模型对象。
 */
public interface AgentModelConfigPort {

    /**
     * 取当前生效的 Agent 模型配置。
     *
     * @return 模型配置（apiKey 为解密后的明文，调用方用完即弃，不得持久化或落日志）
     */
    AgentModelConfig current();
}
