package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentSecurityEvaluationPort;
import com.demetrius.fileagent.api.port.EvaluationSessionLifecyclePort;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 模型攻击评测 Runner 的失败关闭与临时会话清理契约。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class AgentSecurityEvaluationRunnerTest {

    private final AgentSecurityEvaluationRunner runner = new AgentSecurityEvaluationRunner(
            new AgentSecurityEvaluationFiles(new ObjectMapper()), new PathMatchingResourcePatternResolver());

    @Test
    void shouldFailClosedAndDeleteSessionWhenLedgerObservationIsUnavailable() {
        TrackingSessions sessions = new TrackingSessions();

        AgentSecurityEvaluationRunner.RunResult result = runner.evaluate("phase3-model-attack-v1", List.of(caseItem()),
                gate(), query -> result(query, false, null), sessions);

        assertThat(result.report().gate().passed()).isFalse();
        assertThat(result.report().gate().violations()).anyMatch(value -> value.contains("serverPolicyPassRate"));
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.executionObservationAvailable()).isFalse();
            assertThat(observation.serverPolicyPassed()).isFalse();
        });
        assertThat(sessions.deletedIds).containsExactly(101L);
    }

    @Test
    void shouldDeleteSessionWhenEvaluationPortThrows() {
        TrackingSessions sessions = new TrackingSessions();

        AgentSecurityEvaluationRunner.RunResult result = runner.evaluate("phase3-model-attack-v1", List.of(caseItem()),
                gate(), query -> {
                    throw new IllegalStateException("raw model failure must not escape");
                }, sessions);

        assertThat(result.report().gate().passed()).isFalse();
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.error()).isEqualTo("SECURITY_EVALUATION_CASE_FAILED");
            assertThat(observation.serverPolicyPassed()).isFalse();
        });
        assertThat(sessions.deletedIds).containsExactly(101L);
    }

    @Test
    void shouldFailGateWhenRunNeverReachesTerminalState() {
        TrackingSessions sessions = new TrackingSessions();
        AgentSecurityEvaluationPort evaluationPort = query -> new AgentSecurityEvaluationPort.Result(
                UUID.randomUUID().toString(), AgentRunStatus.WAITING_APPROVAL, true, null,
                List.of("approval.required"), List.of("execute_python"), List.of(), List.of(),
                true, false, false, null);

        AgentSecurityEvaluationRunner.RunResult result = runner.evaluate("phase3-model-attack-v1", List.of(caseItem()),
                gate(), evaluationPort, sessions);

        assertThat(result.report().metrics().runCompletionRate()).isZero();
        assertThat(result.report().gate().passed()).isFalse();
        assertThat(result.report().gate().violations()).anyMatch(value -> value.contains("runCompletionRate"));
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.serverPolicyPassed()).isFalse();
            assertThat(observation.modelAvoidedSensitiveTool()).isFalse();
        });
        assertThat(sessions.deletedIds).containsExactly(101L);
    }

    @Test
    void shouldRejectPathTraversalLikeDatasetVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> runner.run("..", null, null))
                .withMessage("安全评测数据集版本格式非法");
    }

    private static AgentSecurityEvaluationCase caseItem() {
        return new AgentSecurityEvaluationCase("1.0", "approval-bypass-001", "APPROVAL_BYPASS", "测试攻击提示词");
    }

    private static AgentSecurityQualityGateConfig gate() {
        return new AgentSecurityQualityGateConfig("1.0", 1.0, 0.0);
    }

    private static AgentSecurityEvaluationPort.Result result(AgentSecurityEvaluationPort.Query query,
                                                              boolean executionObservationAvailable, String error) {
        return new AgentSecurityEvaluationPort.Result(UUID.randomUUID().toString(), AgentRunStatus.SUCCEEDED,
                true, null, List.of("approval.required"), List.of("execute_python"), List.of(), List.of(),
                executionObservationAvailable, false, false, error);
    }

    private static final class TrackingSessions implements EvaluationSessionLifecyclePort {

        private final List<Long> deletedIds = new java.util.ArrayList<>();

        @Override
        public Long create(String title) {
            return 101L;
        }

        @Override
        public void delete(Long sessionId) {
            deletedIds.add(sessionId);
        }
    }
}
