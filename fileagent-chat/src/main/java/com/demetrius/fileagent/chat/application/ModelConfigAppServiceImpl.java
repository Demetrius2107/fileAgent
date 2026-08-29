package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ModelProviderSummary;
import com.demetrius.fileagent.api.dto.SaveModelProviderReq;
import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.chat.infrastructure.DynamicChatModelFactory;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型 Provider 配置用例实现。
 * <p>
 * Key 生命周期：前端明文提交 → AES-GCM 加密 → 密文落 H2 → 使用/测试时解密
 * → 明文仅在构建模型实例的瞬间存在，接口响应只回掩码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigAppServiceImpl implements ModelConfigAppService {

    private final ModelConfigRepository modelConfigRepository;
    private final AesGcmCipher aesGcmCipher;
    private final DynamicChatModelFactory chatModelFactory;
    private final StreamingChatClient streamingChatClient;

    @Override
    public List<ModelProviderSummary> list() {
        return modelConfigRepository.findAllOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public ModelProviderSummary save(SaveModelProviderReq req) {
        if (req.provider() == null) {
            throw new BizException("请选择模型厂商");
        }
        if (!StringUtils.hasText(req.apiKey())) {
            throw new BizException("API Key 不能为空");
        }
        if (!StringUtils.hasText(req.chatModel())) {
            throw new BizException("模型名不能为空");
        }
        String baseUrl = StringUtils.hasText(req.baseUrl())
                ? req.baseUrl()
                : req.provider().getDefaultBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new BizException("自定义 Provider 必须填写 base-url");
        }

        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setProvider(req.provider());
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyCipher(aesGcmCipher.encrypt(req.apiKey()));
        entity.setChatModel(req.chatModel());
        entity.setTemperature(req.temperature());
        // 第一套配置自动启用，省一次手动激活
        if (modelConfigRepository.findActive().isEmpty()) {
            entity.setActive(true);
        }
        ModelConfigEntity saved = modelConfigRepository.save(entity);
        log.info("新增模型配置: provider={}, model={}, active={}", saved.getProvider(), saved.getChatModel(), saved.isActive());
        return toSummary(saved);
    }

    @Override
    @Transactional
    public void activate(Long id) {
        ModelConfigEntity target = modelConfigRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "模型配置不存在: " + id));
        modelConfigRepository.findActive().ifPresent(current -> {
            current.setActive(false);
            modelConfigRepository.save(current);
        });
        target.setActive(true);
        modelConfigRepository.save(target);
        String plain = aesGcmCipher.decrypt(target.getApiKeyCipher());
        streamingChatClient.refresh(chatModelFactory.create(target, plain));
        log.info("切换聊天模型: provider={}, model={}", target.getProvider(), target.getChatModel());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ModelConfigEntity entity = modelConfigRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "模型配置不存在: " + id));
        boolean wasActive = entity.isActive();
        modelConfigRepository.delete(entity);
        chatModelFactory.evict(id);
        if (wasActive) {
            streamingChatClient.useDefault();
            log.info("已删除启用中的模型配置，回落默认模型（环境变量）");
        }
    }

    @Override
    public String test(Long id) {
        ModelConfigEntity entity = modelConfigRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "模型配置不存在: " + id));
        String plain = aesGcmCipher.decrypt(entity.getApiKeyCipher());
        ChatModel model = chatModelFactory.create(entity, plain);
        long start = System.currentTimeMillis();
        try {
            String reply = streamingChatClient.probe(model);
            return "连通正常，耗时 " + (System.currentTimeMillis() - start) + "ms，模型回复: " + reply;
        } catch (Exception e) {
            log.warn("模型连通性测试失败: id={}, provider={}", id, entity.getProvider(), e);
            throw new BizException("连接失败: " + e.getMessage());
        }
    }

    private ModelProviderSummary toSummary(ModelConfigEntity e) {
        return new ModelProviderSummary(
                e.getId(),
                e.getProvider(),
                e.getBaseUrl(),
                e.getChatModel(),
                e.getTemperature(),
                e.isActive(),
                maskKey(e),
                e.getCreatedAt().toString());
    }

    /** 掩码展示：解密取尾 4 位（sk-****ab3f），解密失败降级为纯掩码 */
    private String maskKey(ModelConfigEntity e) {
        try {
            String plain = aesGcmCipher.decrypt(e.getApiKeyCipher());
            return plain.length() <= 4 ? "****" : "****" + plain.substring(plain.length() - 4);
        } catch (Exception ex) {
            return "****";
        }
    }
}
