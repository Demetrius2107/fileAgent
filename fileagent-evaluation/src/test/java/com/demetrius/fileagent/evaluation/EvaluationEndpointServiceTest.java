package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.RagAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationEndpointServiceTest {

    @Test
    void shouldRejectUnsafeKValues() {
        assertThatThrownBy(() -> new EvaluationRunRequest(
                "v1", List.of(1, 101), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 到 100");
    }

    @Test
    void shouldLoadBundledDatasetAndRecordEffectiveRetrievalConfiguration() {
        RagAnswerEvaluationPort port = query -> new RagAnswerEvaluationPort.Result(
                "资料中未提供相关信息，无法确定。", List.of(), List.of(), 1L);
        RagAnswerJudgePort judgePort = request -> new RagAnswerJudgePort.Result(
                request.shouldAnswer() ? RagAnswerJudgePort.Decision.ANSWERED : RagAnswerJudgePort.Decision.REFUSED,
                "决策符合预期", false, "没有无依据内容",
                request.requiredFacts().stream()
                        .map(fact -> new RagAnswerJudgePort.FactAssessment(fact, true, "已覆盖"))
                        .toList(),
                request.forbiddenFacts().stream()
                        .map(fact -> new RagAnswerJudgePort.FactAssessment(fact, false, "未出现"))
                        .toList(), 1L);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.embedding.model", "embedding-v4")
                .withProperty("fileagent.elasticsearch.index-alias", "eval-index")
                .withProperty("fileagent.reranker.enabled", "true")
                .withProperty("fileagent.reranker.model", "reranker-v3");
        EvaluationEndpointService service = new EvaluationEndpointService(port, judgePort, new ObjectMapper(),
                new PathMatchingResourcePatternResolver(), environment);

        EvaluationRunResponse response = service.run(new EvaluationRunRequest(
                "v1", List.of(1, 5, 10), Map.of("purpose", "test"), null));

        assertThat(response.report().totalCases()).isEqualTo(30);
        assertThat(response.observations()).hasSize(30);
        assertThat(response.report().metadata())
                .containsEntry("embeddingModel", "embedding-v4")
                .containsEntry("indexAlias", "eval-index")
                .containsEntry("rerankerEnabled", "true")
                .containsEntry("rerankerModel", "reranker-v3")
                .containsEntry("judgeModel", "deepseek-v4-pro")
                .containsEntry("purpose", "test");
        assertThat(response.report().gate().passed()).isFalse();
        assertThat(response.markdown()).contains("# RAG 端到端评测报告").contains("FAIL");
    }
}
