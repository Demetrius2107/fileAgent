package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ModelProvider;

/**
 * 模型 Provider 配置概要（列表/保存返回）。
 * <p>
 * apiKeyMasked 为掩码形式（如 sk-****ab3f），明文 key 永不出后端。
 *
 * @param id           配置 id
 * @param provider     Provider 枚举名
 * @param baseUrl      OpenAI 兼容端点
 * @param chatModel    聊天模型名
 * @param temperature  采样温度（null 表示用默认 0.2）
 * @param active       是否为当前启用配置
 * @param apiKeyMasked 掩码后的 API Key
 * @param createdAt    创建时间
 */
public record ModelProviderSummary(
        Long id,
        ModelProvider provider,
        String baseUrl,
        String chatModel,
        Double temperature,
        boolean active,
        String apiKeyMasked,
        String createdAt) {
}
