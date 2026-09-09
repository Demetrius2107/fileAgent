package com.demetrius.fileagent.chat.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekJudgeClientTest {

    @Test
    void shouldUseDeepSeekV4ProWithJsonOutput() {
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"decision\":\"ANSWERED\"}")))));
        DeepSeekJudgeClient client = new DeepSeekJudgeClient(chatModel);

        String response = client.call(List.of(new SystemMessage("评判回答")));

        assertThat(response).isEqualTo("{\"decision\":\"ANSWERED\"}");
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        DeepSeekChatOptions options = (DeepSeekChatOptions) captor.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }
}
