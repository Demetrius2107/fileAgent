package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.AgentModelConfig;
import com.demetrius.fileagent.api.enums.ModelProvider;
import com.demetrius.fileagent.api.port.AgentModelConfigPort;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeModelFactoryTest {

    @Test
    void shouldBuildOpenAiCompatibleModelForDeepSeekConfig() {
        AgentModelConfigPort port = () -> new AgentModelConfig(
                ModelProvider.DEEPSEEK, "https://api.deepseek.com/v1", "sk-test", "deepseek-chat", 0.2);
        AgentScopeModelFactory factory = new AgentScopeModelFactory(port);

        Model model = factory.create();

        assertThat(model.getModelName()).isEqualTo("deepseek-chat");
        assertThat(model).isInstanceOf(OpenAIChatModel.class);
    }

    @Test
    void shouldBuildModelForCustomOpenAiCompatibleEndpoint() {
        AgentModelConfigPort port = () -> new AgentModelConfig(
                ModelProvider.CUSTOM, "https://crs.example.pub/v1", "sk-custom", "gpt-5.5", 0.1);
        AgentScopeModelFactory factory = new AgentScopeModelFactory(port);

        Model model = factory.create();

        assertThat(model.getModelName()).isEqualTo("gpt-5.5");
        assertThat(model).isInstanceOf(OpenAIChatModel.class);
    }
}
