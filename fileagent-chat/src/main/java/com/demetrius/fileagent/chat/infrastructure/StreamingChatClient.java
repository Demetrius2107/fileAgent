package com.demetrius.fileagent.chat.infrastructure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流式模型适配器：只包装 ChatClient 的流式调用，
 * 不处理知识检索、持久化或 SSE 事件。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Component
public class StreamingChatClient {

    private final ChatClient chatClient;

    public StreamingChatClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** 以流式方式调用模型，返回 token 增量流 */
    public Flux<String> stream(Prompt prompt) {
        return chatClient.prompt(prompt).stream().content();
    }
}
