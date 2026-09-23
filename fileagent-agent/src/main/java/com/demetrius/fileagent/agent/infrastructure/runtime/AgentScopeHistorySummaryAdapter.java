package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.port.HistorySummaryPort;
import com.demetrius.fileagent.api.dto.MessageDto;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentScope 历史摘要模型适配器：固定 JSON 结构并记录供应商 usage。
 *
 * @author raosaijie
 */
@Component
@RequiredArgsConstructor
public class AgentScopeHistorySummaryAdapter implements HistorySummaryPort {

    private static final List<String> REQUIRED_FIELDS = List.of(
            "confirmedFacts", "userConstraints", "decisions", "openQuestions", "references");

    private final AgentScopeModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    @Override
    public SummaryResult summarize(String existingSummary, List<MessageDto> messages) {
        String prompt = buildPrompt(existingSummary, messages);
        Msg input = Msg.builder()
                .role(MsgRole.USER)
                .textContent(prompt)
                .build();
        Model model = modelFactory.createSummary();
        List<ChatResponse> responses = model.stream(
                List.of(input), List.of(),
                GenerateOptions.builder().temperature(0.0).build())
                .collectList().block();
        if (responses == null || responses.isEmpty()) {
            throw new IllegalArgumentException("摘要模型没有返回结果");
        }
        String text = responses.stream()
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
        ChatUsage usage = responses.stream()
                .map(ChatResponse::getUsage)
                .filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(new ChatUsage(0, 0, 0));
        return parseSummaryText(text, usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
    }

    SummaryResult parseSummaryText(String text, int inputTokens, int outputTokens, int totalTokens) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("摘要必须是 JSON 对象");
            }
            for (String field : REQUIRED_FIELDS) {
                JsonNode value = root.get(field);
                if (value == null || !value.isArray()) {
                    throw new IllegalArgumentException("摘要缺少数组字段: " + field);
                }
            }
            return new SummaryResult(objectMapper.writeValueAsString(root),
                    inputTokens, outputTokens, totalTokens);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("摘要 JSON 不合法", e);
        }
    }

    private String buildPrompt(String existingSummary, List<MessageDto> messages) {
        StringBuilder prompt = new StringBuilder("""
                你是会话历史摘要器。只能提取消息中明确出现的信息，不得补充推断或常识。
                必须保留否定词、数字、单位、日期、文件名和 ID。只返回 JSON，不要 Markdown，不要解释。
                JSON 必须包含 confirmedFacts、userConstraints、decisions、openQuestions、references 五个数组。
                已有摘要：
                """);
        prompt.append(existingSummary == null ? "{}" : existingSummary).append("\n新增历史消息：\n");
        for (MessageDto message : messages) {
            prompt.append(message.role()).append(": ").append(message.content()).append("\n");
        }
        return prompt.toString();
    }
}
