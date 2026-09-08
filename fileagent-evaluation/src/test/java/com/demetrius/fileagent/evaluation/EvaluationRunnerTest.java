package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {

    @Test
    void shouldCollectHitsWithCaseFiltersAndKeepFailuresIsolated() {
        List<KnowledgeSearchPort.SearchQuery> queries = new ArrayList<>();
        KnowledgeSearchPort port = new KnowledgeSearchPort() {
            @Override
            public List<KnowledgeHit> search(String query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<KnowledgeHit> search(SearchQuery query) {
                queries.add(query);
                if (query.text().equals("失败问题")) {
                    throw new IllegalStateException("ES unavailable");
                }
                return List.of(new KnowledgeHit("chunk-1", 1L, "正文", "policy.md",
                        null, "section-1", "parent-1", 2, 0.88));
            }
        };
        EvaluationRunner runner = new EvaluationRunner(port);

        List<EvaluationObservation> observations = runner.collect(List.of(
                evaluationCase("ok", "正常问题"),
                evaluationCase("failed", "失败问题")
        ));

        assertThat(queries.get(0)).isEqualTo(new KnowledgeSearchPort.SearchQuery(
                "正常问题", "eval", "baseline", 9L));
        assertThat(observations.get(0).retrieved()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo("chunk-1");
            assertThat(source.filename()).isEqualTo("policy.md");
            assertThat(source.score()).isEqualTo(0.88);
        });
        assertThat(observations.get(1).error()).contains("IllegalStateException").contains("ES unavailable");
    }

    private static EvaluationCase evaluationCase(String id, String question) {
        return new EvaluationCase("1.0", id, "FACT", List.of(), question, List.of(),
                new EvaluationCase.Filters("eval", "baseline", 9L),
                new EvaluationCase.Expected(true, List.of(), List.of(), List.of()));
    }
}
