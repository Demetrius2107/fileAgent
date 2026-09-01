package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ModelProvider;

/**
 * 新增/保存模型 Provider 配置请求。
 *
 * @param provider    Provider 枚举名（DEEPSEEK/ZHIPU/DASHSCOPE/MOONSHOT/OPENAI/CUSTOM）
 * @param baseUrl     OpenAI 兼容端点；为空时使用 Provider 默认值（CUSTOM 必填）
 * @param apiKey      API Key 明文（后端 AES-GCM 加密后落库，不落明文）
 * @param chatModel   聊天模型名（如 deepseek-chat / glm-4.6 / qwen-max）
 * @param temperature 采样温度；为空时用默认 0.2
 */
public record SaveModelProviderReq(
        ModelProvider provider,
        String baseUrl,
        String apiKey,
        String chatModel,
        Double temperature) {
}
