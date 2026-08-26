package com.demetrius.fileagent.chat.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StreamingChatClient} 适配测试：只包装模型调用，返回 token 流。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
class StreamingChatClientTest {

    @Test
    void streamShouldReturnTokenFluxFromChatClient() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("你", "好"));

        StreamingChatClient streamingChatClient = new StreamingChatClient(builder);
        Prompt prompt = new Prompt(List.of(new UserMessage("你好")));

        StepVerifier.create(streamingChatClient.stream(prompt))
                .expectNext("你")
                .expectNext("好")
                .verifyComplete();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(captor.capture());
        assertThat(captor.getValue()).isSameAs(prompt);
    }
}
