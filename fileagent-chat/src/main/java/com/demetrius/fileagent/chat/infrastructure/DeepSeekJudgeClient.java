package com.demetrius.fileagent.chat.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 使用固定 DeepSeek 模型执行回答质量评判。
 *
 * @author raosaijie
 */
@Component
@RequiredArgsConstructor
public class DeepSeekJudgeClient {

    private final DeepSeekChatModel chatModel;

    public String call(List<Message> messages) {
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(DeepSeekApi.ChatModel.DEEPSEEK_V4_PRO)
                .temperature(0.0)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("DeepSeek Judge 未返回评判结果");
        }
        return response.getResult().getOutput().getText();
    }
}
