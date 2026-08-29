package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态聊天模型工厂：按前端录入的 Provider 配置构建 OpenAI 兼容的 {@link ChatModel}。
 * <p>
 * 所有支持的厂商（DeepSeek/智谱/通义/Kimi/OpenAI）都兼容 chat/completions 协议，
 * 统一用 OpenAI 官方 SDK 客户端（{@link OpenAIClientImpl}）指向各自 base-url 即可，
 * HTTP 层复用 Spring AI 的 {@link SpringAiOpenAiHttpClient}。
 * 模型实例按配置 id 缓存复用（配置 key/模型名变更时由调用方先 {@link #evict}）。
 */
@Component
public class DynamicChatModelFactory {

    /** 统一的 completions 路径：默认 base-url 已含版本段（如 /v1、/v4） */
    private static final String COMPLETIONS_PATH = "/chat/completions";

    private static final double DEFAULT_TEMPERATURE = 0.2;

    private final Map<Long, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 按配置构建（或取缓存）ChatModel。
     *
     * @param config      模型配置
     * @param plainApiKey 解密后的 API Key 明文（由调用方解密，工厂不持有密文）
     * @return 可直接构建 ChatClient 的 ChatModel
     */
    public ChatModel create(ModelConfigEntity config, String plainApiKey) {
        return cache.computeIfAbsent(config.getId(), id -> doCreate(config, plainApiKey));
    }

    /** 配置被编辑/删除时清除对应缓存，下次重建 */
    public void evict(Long configId) {
        cache.remove(configId);
    }

    private ChatModel doCreate(ModelConfigEntity config, String plainApiKey) {
        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .baseUrl(config.getBaseUrl())
                .apiKey(plainApiKey)
                .build();
        OpenAIClient client = new OpenAIClientImpl(clientOptions);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getChatModel())
                .temperature(config.getTemperature() == null ? DEFAULT_TEMPERATURE : config.getTemperature())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(options)
                .build();
    }
}
