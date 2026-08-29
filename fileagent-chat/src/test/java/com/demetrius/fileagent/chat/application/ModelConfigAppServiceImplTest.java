package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ModelProviderSummary;
import com.demetrius.fileagent.api.dto.SaveModelProviderReq;
import com.demetrius.fileagent.api.enums.ModelProvider;
import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.chat.infrastructure.DynamicChatModelFactory;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelConfigAppServiceImpl} 用例测试：key 加密落库、掩码返回、
 * 启用热切换、删除启用配置回落默认。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-29
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigAppServiceImplTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private AesGcmCipher aesGcmCipher;

    @Mock
    private DynamicChatModelFactory chatModelFactory;

    @Mock
    private StreamingChatClient streamingChatClient;

    @InjectMocks
    private ModelConfigAppServiceImpl modelConfigAppService;

    @Test
    void saveShouldEncryptKeyAndAutoActivateFirstConfig() {
        when(aesGcmCipher.encrypt("sk-plain")).thenReturn("cipher-text");
        when(modelConfigRepository.findActive()).thenReturn(Optional.empty());
        when(modelConfigRepository.save(any(ModelConfigEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelProviderSummary summary = modelConfigAppService.save(
                new SaveModelProviderReq(ModelProvider.ZHIPU, null, "sk-plain", "glm-4.6", null));

        ArgumentCaptor<ModelConfigEntity> captor = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigRepository).save(captor.capture());
        ModelConfigEntity saved = captor.getValue();
        // key 必须以密文落库，端点回填智谱默认值，首套自动启用
        assertThat(saved.getApiKeyCipher()).isEqualTo("cipher-text").isNotEqualTo("sk-plain");
        assertThat(saved.getBaseUrl()).isEqualTo(ModelProvider.ZHIPU.getDefaultBaseUrl());
        assertThat(saved.isActive()).isTrue();
        assertThat(summary.apiKeyMasked()).isEqualTo("****");
    }

    @Test
    void saveShouldRejectBlankApiKey() {
        assertThatThrownBy(() -> modelConfigAppService.save(
                new SaveModelProviderReq(ModelProvider.DEEPSEEK, null, " ", "deepseek-chat", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API Key");
        verify(modelConfigRepository, never()).save(any());
    }

    @Test
    void activateShouldDeactivateOthersAndHotRefresh() {
        ModelConfigEntity current = entity(1L, true);
        ModelConfigEntity target = entity(2L, false);
        when(modelConfigRepository.findById(2L)).thenReturn(Optional.of(target));
        when(modelConfigRepository.findActive()).thenReturn(Optional.of(current));
        when(modelConfigRepository.save(any(ModelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(aesGcmCipher.decrypt("cipher-2")).thenReturn("sk-plain-2");
        ChatModel model = mock(ChatModel.class);
        when(chatModelFactory.create(target, "sk-plain-2")).thenReturn(model);

        modelConfigAppService.activate(2L);

        assertThat(current.isActive()).isFalse();
        assertThat(target.isActive()).isTrue();
        verify(streamingChatClient).refresh(model);
    }

    @Test
    void deleteActiveConfigShouldFallbackToDefaultModel() {
        ModelConfigEntity active = entity(1L, true);
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(active));

        modelConfigAppService.delete(1L);

        verify(modelConfigRepository).delete(active);
        verify(chatModelFactory).evict(1L);
        verify(streamingChatClient).useDefault();
    }

    @Test
    void deleteInactiveConfigShouldKeepCurrentModel() {
        ModelConfigEntity inactive = entity(2L, false);
        when(modelConfigRepository.findById(2L)).thenReturn(Optional.of(inactive));

        modelConfigAppService.delete(2L);

        verify(streamingChatClient, never()).useDefault();
        verify(streamingChatClient, never()).refresh(any());
    }

    @Test
    void testShouldReportLatencyOnSuccessAndWrapErrorOnFailure() {
        ModelConfigEntity entity = entity(1L, true);
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(aesGcmCipher.decrypt("cipher-1")).thenReturn("sk-plain-1");
        ChatModel model = mock(ChatModel.class);
        when(chatModelFactory.create(entity, "sk-plain-1")).thenReturn(model);
        when(streamingChatClient.probe(model)).thenReturn("正常");

        String result = modelConfigAppService.test(1L);
        assertThat(result).contains("连通正常").contains("正常");

        when(streamingChatClient.probe(model)).thenThrow(new RuntimeException("401 Unauthorized"));
        assertThatThrownBy(() -> modelConfigAppService.test(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("连接失败");
    }

    private ModelConfigEntity entity(Long id, boolean active) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setProvider(ModelProvider.DEEPSEEK);
        entity.setBaseUrl(ModelProvider.DEEPSEEK.getDefaultBaseUrl());
        entity.setApiKeyCipher("cipher-" + id);
        entity.setChatModel("deepseek-chat");
        entity.setActive(active);
        return entity;
    }
}
