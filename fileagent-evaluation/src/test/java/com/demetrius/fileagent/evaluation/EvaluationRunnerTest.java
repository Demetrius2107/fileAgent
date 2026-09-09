package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {

    @Test
    void shouldCollectHitsWithCaseFiltersAndKeepFailuresIsolated() {
        List<RagAnswerEvaluationPort.Query> queries = new ArrayList<>();
        RagAnswerEvaluationPort port = query -> {
                queries.add(query);
                if (query.question().equals("失败问题")) {
                    throw new IllegalStateException("ES unavailable");
                }
                KnowledgeSearchPort.KnowledgeHit hit = new KnowledgeSearchPort.KnowledgeHit(
                        "chunk-1", 1L, "正文", "policy.md",
                        null, "section-1", "parent-1", 2, 0.88);
                return new RagAnswerEvaluationPort.Result(
                        "这是实际回答", List.of(hit), List.of(hit), 15L);
        };
        RagAnswerJudgePort judgePort = request -> new RagAnswerJudgePort.Result(
                RagAnswerJudgePort.Decision.ANSWERED, "回答了问题", false, "没有无依据内容",
                List.of(new RagAnswerJudgePort.FactAssessment("答案要点", true, "语义覆盖")), List.of(), 5L);
        EvaluationRunner runner = new EvaluationRunner(port, judgePort);

        List<EvaluationObservation> observations = runner.collect(List.of(
                evaluationCase("ok", "正常问题"),
                evaluationCase("failed", "失败问题")
        ));

        assertThat(queries.get(0)).isEqualTo(new RagAnswerEvaluationPort.Query(
                "正常问题", List.of(), "eval", "baseline", 9L));
        assertThat(observations.get(0).retrieved()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo("chunk-1");
            assertThat(source.filename()).isEqualTo("policy.md");
            assertThat(source.score()).isEqualTo(0.88);
        });
        assertThat(observations.get(0).answer()).isEqualTo("这是实际回答");
        assertThat(observations.get(0).refused()).isFalse();
        assertThat(observations.get(0).citations()).hasSize(1);
        assertThat(observations.get(0).judgeScores())
                .containsEntry("requiredFactCoverage", 1.0)
                .containsEntry("answerDecisionAccuracy", 1.0)
                .containsEntry("unsupportedClaimSafety", 1.0);
        assertThat(observations.get(0).judgeReasons().get("requiredFactCoverage")).contains("语义覆盖");
        assertThat(observations.get(0).durationMs()).isEqualTo(20L);
        assertThat(observations.get(1).error()).contains("IllegalStateException").contains("ES unavailable");
    }

    private static EvaluationCase evaluationCase(String id, String question) {
        return new EvaluationCase("1.0", id, "FACT", List.of(), question, List.of(),
                new EvaluationCase.Filters("eval", "baseline", 9L),
                new EvaluationCase.Expected(true, List.of(), List.of("答案要点"), List.of()));
    }
}
