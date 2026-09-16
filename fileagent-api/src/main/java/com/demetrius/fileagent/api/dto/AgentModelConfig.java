package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ModelProvider;

/**
 * Agent 运行时使用的模型配置（不可变，进程内传递）。
 * <p>
 * 仅由服务端可信配置装配，绝不写入响应、SSE 事件或日志（apiKey 尤其敏感）。
 *
 * @param provider    模型厂商（决定 AgentScope 侧的 message formatter）
 * @param baseUrl     OpenAI 兼容端点
 * @param apiKey      解密后的明文 Key，仅在构建模型实例的瞬间存在
 * @param model       模型名（如 deepseek-chat / glm-4.6 / qwen-max）
 * @param temperature 采样温度
 */
public record AgentModelConfig(
        ModelProvider provider,
        String baseUrl,
        String apiKey,
        String model,
        double temperature) {
}
