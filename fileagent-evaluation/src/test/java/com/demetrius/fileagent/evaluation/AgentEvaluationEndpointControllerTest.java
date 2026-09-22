package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentEvaluationEndpointControllerTest {

    @Mock
    private AgentEvaluationEndpointService agentEvaluationEndpointService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        EvaluationEndpointProperties properties = new EvaluationEndpointProperties();
        properties.setToken("evaluation-secret");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgentEvaluationEndpointController(agentEvaluationEndpointService, properties)).build();
    }

    @Test
    void shouldRejectRequestWithoutValidToken() throws Exception {
        mockMvc.perform(post("/internal/evaluation/agent/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(agentEvaluationEndpointService, never()).run(any());
    }

    @Test
    void shouldRunAgentEvaluationWhenTokenIsValid() throws Exception {
        AgentEvaluationReport report = new AgentEvaluationReport("1.0", "agent-v1", "2026-09-17T00:00:00Z",
                8, 8, 0, new AgentEvaluationReport.AnswerMetrics(1, 1, 1.0, 1),
                new AgentEvaluationReport.AgentMetrics(1, 1, 1, 1, 1, 1, 1, 1),
                List.of(), new AgentEvaluationReport.GateResult(true, List.of()));
        when(agentEvaluationEndpointService.run(any())).thenReturn(
                new AgentEvaluationRunResponse(report, List.of(), "# Agent 评测报告"));

        mockMvc.perform(post("/internal/evaluation/agent/run")
                        .header(AgentEvaluationEndpointController.TOKEN_HEADER, "evaluation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"agent-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report.datasetVersion").value("agent-v1"))
                .andExpect(jsonPath("$.data.report.gate.passed").value(true));

        verify(agentEvaluationEndpointService).run(any());
    }

    @Test
    void shouldSerializeMissingForbiddenFactSafetyAsNull() throws Exception {
        AgentEvaluationReport report = new AgentEvaluationReport("1.0", "agent-v1", "now", 1, 1, 0,
                new AgentEvaluationReport.AnswerMetrics(1, 1, null, 1),
                new AgentEvaluationReport.AgentMetrics(1, 1, 1, 1, 1, 1, 1, 1),
                List.of(), new AgentEvaluationReport.GateResult(true, List.of()));
        when(agentEvaluationEndpointService.run(any())).thenReturn(
                new AgentEvaluationRunResponse(report, List.of(), "# Agent 评测报告"));

        mockMvc.perform(post("/internal/evaluation/agent/run")
                        .header(AgentEvaluationEndpointController.TOKEN_HEADER, "evaluation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"agent-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.answerMetrics.forbiddenFactSafety")
                        .value(org.hamcrest.Matchers.nullValue()));
    }
}
