package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
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
        KnowledgeSearchPort port = query -> List.of();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.embedding.model", "embedding-v4")
                .withProperty("fileagent.elasticsearch.index-alias", "eval-index")
                .withProperty("fileagent.reranker.enabled", "true")
                .withProperty("fileagent.reranker.model", "reranker-v3");
        EvaluationEndpointService service = new EvaluationEndpointService(port, new ObjectMapper(),
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
                .containsEntry("purpose", "test");
        assertThat(response.report().gate().passed()).isFalse();
        assertThat(response.markdown()).contains("# RAG 评测报告").contains("FAIL");
    }
}
