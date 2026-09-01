package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型调用适配器：包装 ChatClient 的同步与流式调用，
 * 不处理知识检索、持久化或 SSE 事件。
 * <p>
 * 支持运行时切换模型：启动时优先使用数据库中 active 的 Provider 配置
 * （前端录入），无配置或构建失败时回落到自动配置的默认模型
 * （application.yml + 环境变量），保证存量部署行为不变。
 * 切换入口在 {@link #refresh(ChatModel)} / {@link #useDefault()}，
 * 由模型配置服务在启用/删除配置时调用，热生效无需重启。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Slf4j
@Component
public class StreamingChatClient {

    private final ChatClient.Builder defaultClientBuilder;
    private final ModelConfigRepository modelConfigRepository;
    private final DynamicChatModelFactory chatModelFactory;
    private final AesGcmCipher aesGcmCipher;

    private final AtomicReference<ChatClient> clientRef = new AtomicReference<>();

    public StreamingChatClient(ChatClient.Builder defaultClientBuilder,
                               ModelConfigRepository modelConfigRepository,
                               DynamicChatModelFactory chatModelFactory,
                               AesGcmCipher aesGcmCipher) {
        this.defaultClientBuilder = defaultClientBuilder;
        this.modelConfigRepository = modelConfigRepository;
        this.chatModelFactory = chatModelFactory;
        this.aesGcmCipher = aesGcmCipher;
    }

    /** 启动时选定初始模型：DB active 配置优先，失败/缺失回落默认 */
    @PostConstruct
    void initActiveOrDefault() {
        modelConfigRepository.findActive().ifPresentOrElse(config -> {
            try {
                ChatModel model = chatModelFactory.create(config, aesGcmCipher.decrypt(config.getApiKeyCipher()));
                clientRef.set(ChatClient.builder(model).build());
                log.info("已启用前端配置的聊天模型: provider={}, model={}", config.getProvider(), config.getChatModel());
            } catch (Exception e) {
                log.warn("启用模型配置失败（id={}），回落默认模型: {}", config.getId(), e.getMessage());
                useDefault();
            }
        }, this::useDefault);
    }

    /** 以流式方式调用模型，返回 token 增量流 */
    public Flux<String> stream(Prompt prompt) {
        return clientRef.get().prompt(prompt).stream().content();
    }

    /** 返回模型一次调用的完整文本，供检索规划与结果评估使用 */
    public String call(Prompt prompt) {
        return clientRef.get().prompt(prompt).call().content();
    }

    /**
     * 热切换到指定模型（前端启用某套配置时调用）。
     *
     * @param model 新构建的 ChatModel
     */
    public void refresh(ChatModel model) {
        clientRef.set(ChatClient.builder(model).build());
    }

    /** 回落到自动配置的默认模型（删除 active 配置或配置失效时调用） */
    public void useDefault() {
        clientRef.set(defaultClientBuilder.build());
    }

    /**
     * 用指定模型做一次最小连通性验证（不切换当前生效模型）。
     *
     * @param model 待验证的 ChatModel
     * @return 模型回复文本
     */
    public String probe(ChatModel model) {
        return ChatClient.builder(model).build()
                .prompt()
                .user("请只回复两个字：正常")
                .call()
                .content();
    }
}
