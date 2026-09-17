package com.demetrius.fileagent.agent.application.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptFactoryTest {

    @Test
    void promptShouldLayerJudgmentAndKeepEvidenceRules() {
        String prompt = new AgentPromptFactory().systemInstruction();

        // 分层判断：常识和正常创作直答 / 企业事实检索 / 安全越界拒绝
        assertThat(prompt).contains("先判断问题类型");
        assertThat(prompt).contains("直接简短回答，无需检索");
        assertThat(prompt).contains("正常创作请求");
        assertThat(prompt).contains("安全越界请求");

        // 证据与反幻觉核心
        assertThat(prompt).contains("[来源：文件名]");
        assertThat(prompt).contains("知识库中未找到相关资料");

        // 放开过程说明，但仍禁止冗长内部推理链
        assertThat(prompt).contains("简要说明正在做什么");
        assertThat(prompt).contains("不要输出冗长的内部推理链");
    }
}
