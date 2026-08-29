package com.demetrius.fileagent.chat.domain;

import com.demetrius.fileagent.api.enums.ModelProvider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天模型 Provider 配置（前端录入，多套并存，同一时刻至多一套 active）。
 * <p>
 * API Key 以 AES-GCM 密文落库（{@code api_key_cipher}），明文只存在于
 * 构建/测试模型调用的瞬间，永不出后端。无任何 active 配置时，chat 域
 * 回落到 application.yml + 环境变量的默认模型（兼容存量部署）。
 */
@Getter
@Setter
@Entity
@Table(name = "model_provider_config")
public class ModelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型厂商（决定默认 base-url） */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private ModelProvider provider;

    /** OpenAI 兼容端点（可覆盖厂商默认值） */
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** API Key 密文（AES-GCM，Base64(IV+密文)） */
    @Lob
    @Column(name = "api_key_cipher", columnDefinition = "CLOB", nullable = false)
    private String apiKeyCipher;

    /** 聊天模型名（如 deepseek-chat / glm-4.6 / qwen-max） */
    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    /** 采样温度；null 表示用默认 0.2 */
    @Column(name = "temperature")
    private Double temperature;

    /** 是否为当前启用配置（全局至多一套） */
    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
