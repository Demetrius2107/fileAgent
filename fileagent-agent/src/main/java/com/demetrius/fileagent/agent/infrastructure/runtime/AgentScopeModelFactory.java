package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.AgentModelConfig;
import com.demetrius.fileagent.api.enums.ModelProvider;
import com.demetrius.fileagent.api.port.AgentModelConfigPort;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AgentScope 模型工厂：按可信的 {@link AgentModelConfig} 构建 OpenAI 兼容模型。
 * <p>
 * 模型名、base-url、provider formatter 的选择集中在此；每次 run 调用一次以支持
 * 活动模型热切换。日志只记录 provider、模型名与脱敏后的 base-url，绝不记录 apiKey。
 */
@Slf4j
@Component
public class AgentScopeModelFactory {

    private final AgentModelConfigPort agentModelConfigPort;
    private final AgentProperties agentProperties;

    public AgentScopeModelFactory(AgentModelConfigPort agentModelConfigPort, AgentProperties agentProperties) {
        this.agentModelConfigPort = agentModelConfigPort;
        this.agentProperties = agentProperties;
    }

    /**
     * 依据当前启用配置构建一个流式 AgentScope 模型（OpenAI 兼容）。
     *
     * @return 可直接交给 {@code ReActAgent} 的模型实例
     */
    public Model create() {
        AgentModelConfig config = agentModelConfigPort.current();
        return create(config, config.temperature());
    }

    /** 创建历史摘要专用模型：固定温度 0，和正式回答共享当前模型配置。 */
    public Model createSummary() {
        AgentModelConfig config = agentModelConfigPort.current();
        return create(config, 0.0);
    }

    private Model create(AgentModelConfig config, double temperature) {
        GenerateOptions options = GenerateOptions.builder()
                .temperature(temperature)
                .maxTokens(agentProperties.getMaxOutputTokens())
                .build();
        Model model = OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.model())
                .formatter(formatterFor(config.provider()))
                .generateOptions(options)
                .stream(true)
                .build();
        log.info("构建 AgentScope 模型: provider={}, model={}, baseUrl={}",
                config.provider(), config.model(), mask(config.baseUrl()));
        return model;
    }

    private Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatterFor(ModelProvider provider) {
        return switch (provider) {
            case DEEPSEEK -> new DeepSeekFormatter();
            case ZHIPU -> new GLMFormatter();
            case MOONSHOT -> new KimiFormatter();
            case DASHSCOPE, OPENAI, CUSTOM -> new OpenAIChatFormatter();
        };
    }

    /** 脱敏 base-url：隐藏 host，避免日志泄露内网/中转地址。 */
    private String mask(String baseUrl) {
        return baseUrl == null ? null : baseUrl.replaceAll("//[^/]+", "//***");
    }
}
