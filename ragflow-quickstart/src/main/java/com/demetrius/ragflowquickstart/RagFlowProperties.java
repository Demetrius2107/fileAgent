package com.demetrius.ragflowquickstart;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAGFlow 服务连接配置，API Key 只允许通过运行环境注入。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
@ConfigurationProperties(prefix = "ragflow")
public record RagFlowProperties(
        @DefaultValue("https://cloud.ragflow.io") String baseUrl,
        @DefaultValue("") String apiKey) {

    /** 返回不带尾斜杠且包含 API 版本的服务地址。 */
    public String apiBaseUrl() {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/api/v1") ? normalized : normalized + "/api/v1";
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
