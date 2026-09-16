package com.demetrius.fileagent.chat.infrastructure.model;

import com.demetrius.fileagent.api.dto.AgentModelConfig;
import com.demetrius.fileagent.api.enums.ModelProvider;
import com.demetrius.fileagent.api.port.AgentModelConfigPort;
import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Agent 模型配置端口实现：复用 chat 域的启用模型配置与密钥解密。
 * <p>
 * 优先级：数据库 active 配置（前端录入）&gt; 环境变量默认模型（兼容存量部署）。
 * 解密失败时回落默认配置并记录 warn，绝不把异常抛给 Agent 运行时。
 */
@Slf4j
@Component
public class AgentModelConfigPortImpl implements AgentModelConfigPort {

    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final ModelConfigRepository modelConfigRepository;
    private final AesGcmCipher aesGcmCipher;

    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final double defaultTemperature;

    public AgentModelConfigPortImpl(
            ModelConfigRepository modelConfigRepository,
            AesGcmCipher aesGcmCipher,
            @Value("${spring.ai.deepseek.base-url:}") String defaultBaseUrl,
            @Value("${spring.ai.deepseek.api-key:}") String defaultApiKey,
            @Value("${spring.ai.deepseek.chat.model:}") String defaultModel,
            @Value("${spring.ai.deepseek.chat.temperature:0.2}") double defaultTemperature) {
        this.modelConfigRepository = modelConfigRepository;
        this.aesGcmCipher = aesGcmCipher;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultApiKey = defaultApiKey;
        this.defaultModel = defaultModel;
        this.defaultTemperature = defaultTemperature;
    }

    @Override
    public AgentModelConfig current() {
        Optional<ModelConfigEntity> active = modelConfigRepository.findActive();
        if (active.isPresent()) {
            ModelConfigEntity entity = active.get();
            try {
                return new AgentModelConfig(
                        entity.getProvider(),
                        entity.getBaseUrl(),
                        aesGcmCipher.decrypt(entity.getApiKeyCipher()),
                        entity.getChatModel(),
                        entity.getTemperature() == null ? DEFAULT_TEMPERATURE : entity.getTemperature());
            } catch (Exception e) {
                log.warn("解密启用模型配置失败（id={}），回落环境变量默认模型: {}", entity.getId(), e.getMessage());
            }
        }
        return new AgentModelConfig(
                ModelProvider.DEEPSEEK, defaultBaseUrl, defaultApiKey, defaultModel, defaultTemperature);
    }
}
