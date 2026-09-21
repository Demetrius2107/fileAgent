package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationEndpointServiceTest {

    @Test
    void shouldRunEachBundledCaseThroughAgentPortAndPreserveGeneralKnowledgeModeForJudge() {
        AtomicInteger agentCallCount = new AtomicInteger();
        AtomicBoolean generalKnowledgeObserved = new AtomicBoolean();
        AgentAnswerEvaluationPort agentPort = query -> {
            agentCallCount.incrementAndGet();
            return new AgentAnswerEvaluationPort.Result("评测回答", false,
                    List.of(), List.of(), 0, 1, List.of(), 1L, AgentRunStatus.SUCCEEDED, null, null);
        };
        RagAnswerJudgePort judgePort = request -> {
            if (request.groundingMode() == AnswerGroundingMode.GENERAL_KNOWLEDGE) {
                generalKnowledgeObserved.set(true);
            }
            return new RagAnswerJudgePort.Result(
                    request.shouldAnswer() ? RagAnswerJudgePort.Decision.ANSWERED : RagAnswerJudgePort.Decision.REFUSED,
                    "评测完成", false, null,
                    request.requiredFacts().stream()
                            .map(fact -> new RagAnswerJudgePort.FactAssessment(fact, true, "覆盖"))
                            .toList(),
                    List.of(), "{}", 1L);
        };
        AgentEvaluationEndpointService service = new AgentEvaluationEndpointService(agentPort, judgePort,
                new ObjectMapper(), new PathMatchingResourcePatternResolver());

        AgentEvaluationRunResponse response = service.run(AgentEvaluationRunRequest.defaults());

        assertThat(agentCallCount.get()).isEqualTo(8);
        assertThat(generalKnowledgeObserved).isTrue();
        assertThat(response.observations()).hasSize(8);
        assertThat(response.report().gate()).isNotNull();
    }
}
