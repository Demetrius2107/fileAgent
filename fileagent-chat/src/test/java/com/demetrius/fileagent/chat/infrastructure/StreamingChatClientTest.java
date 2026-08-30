package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
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
        ChatClient chatClient = mockDefaultClient();
        StreamingChatClient streamingChatClient = newClient(chatClient);
        Prompt prompt = new Prompt(List.of(new UserMessage("你好")));

        StepVerifier.create(streamingChatClient.stream(prompt))
                .expectNext("你")
                .expectNext("好")
                .verifyComplete();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(captor.capture());
        assertThat(captor.getValue()).isSameAs(prompt);
    }

    @Test
    void callShouldReturnCompleteContentFromChatClient() {
        ChatClient chatClient = mockDefaultClient();
        StreamingChatClient streamingChatClient = newClient(chatClient);
        Prompt prompt = new Prompt(List.of(new UserMessage("规划检索")));

        assertThat(streamingChatClient.call(prompt)).isEqualTo("{\"needRetrieval\":true}");
        verify(chatClient).prompt(prompt);
    }

    /** 构造被测对象：mock 自动配置 Builder，并用 useDefault() 模拟"无 DB 配置回落默认"的初始化 */
    private StreamingChatClient newClient(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("你", "好"));
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("{\"needRetrieval\":true}");

        StreamingChatClient client = new StreamingChatClient(
                builder, mock(ModelConfigRepository.class), mock(DynamicChatModelFactory.class), mock(AesGcmCipher.class));
        client.useDefault();
        return client;
    }

    private ChatClient mockDefaultClient() {
        return mock(ChatClient.class);
    }
}
