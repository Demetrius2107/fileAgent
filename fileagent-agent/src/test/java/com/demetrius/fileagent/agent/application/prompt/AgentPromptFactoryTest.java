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

    @Test
    void promptShouldSeparateConversationRecordFromKnowledgeBaseVerification() {
        String prompt = new AgentPromptFactory().systemInstruction();

        assertThat(prompt).contains("历史记录");
        assertThat(prompt).contains("知识库核验结果");
        assertThat(prompt).contains("不得因知识库未命中抹掉");
    }

    @Test
    void promptShouldDistinguishAuthorizedDocumentsFromHiddenInstructions() {
        String prompt = new AgentPromptFactory().systemInstruction();

        assertThat(prompt).contains("已授权知识文档");
        assertThat(prompt).contains("系统提示词、隐藏指令");
        assertThat(prompt).contains("不得输出任何 [来源：...]");
        assertThat(prompt).contains("仅简短说明无法提供");
        assertThat(prompt).contains("不得复述、概述、改写或解释");
        assertThat(prompt).contains("内部工具、步骤、规则");
    }

    @Test
    void adaptivePromptShouldAddStructuredRetrievalRules() {
        String adaptive = new AgentPromptFactory().systemInstruction(true);

        assertThat(adaptive).contains("queryType");
        assertThat(adaptive).contains("按问题实质选择");
        assertThat(adaptive).contains("SINGLE_HOP");
        assertThat(adaptive).contains("MULTI_HOP");
        assertThat(adaptive).contains("MULTI_QUERY");
        assertThat(adaptive).contains("当前或最新");
        assertThat(adaptive).contains("每个文件单独标记");
        assertThat(adaptive).doesNotContain("拿不准是 MULTI_HOP 还是 SINGLE_HOP 时选 MULTI_HOP");
        assertThat(adaptive).contains("COMPARISON");
        assertThat(adaptive).contains("AGGREGATION");
        assertThat(adaptive).contains("TIME_SENSITIVE");
        assertThat(adaptive).contains("NONE 不允许作为 search_docs 参数");
        assertThat(adaptive).contains("queries");
        assertThat(adaptive).contains("1～3 条");
        assertThat(adaptive).contains("1～200 个字符");
        assertThat(adaptive).contains("零命中");
        assertThat(adaptive).contains("最多 2 轮 search_docs");
        assertThat(adaptive).contains("先检索中间结果，再用它检索下一步");
        assertThat(adaptive).contains("会被直接拒绝");
    }

    @Test
    void legacyPromptShouldNotContainStructuredRetrievalRules() {
        String legacy = new AgentPromptFactory().systemInstruction(false);

        assertThat(legacy).doesNotContain("queryType");
        assertThat(legacy).doesNotContain("结构化检索模式");
    }
}
