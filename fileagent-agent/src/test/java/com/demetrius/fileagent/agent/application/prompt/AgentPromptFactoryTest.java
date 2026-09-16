package com.demetrius.fileagent.agent.application.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptFactoryTest {

    @Test
    void promptShouldRequireEvidenceAndForbidThoughtChainDisclosure() {
        String prompt = new AgentPromptFactory().systemInstruction();

        assertThat(prompt).contains("没有可靠证据时明确说明无法确认");
        assertThat(prompt).contains("不得输出内部推理过程");
        assertThat(prompt).contains("[来源：文件名]");
    }
}
