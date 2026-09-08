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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EvaluationEndpointControllerTest {

    @Mock
    private EvaluationEndpointService evaluationEndpointService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        EvaluationEndpointProperties properties = new EvaluationEndpointProperties();
        properties.setToken("evaluation-secret");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new EvaluationEndpointController(evaluationEndpointService, properties)).build();
    }

    @Test
    void shouldRejectRequestWithoutValidToken() throws Exception {
        mockMvc.perform(post("/internal/evaluation/rag/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(evaluationEndpointService, never()).run(any());
    }

    @Test
    void shouldRunEvaluationWhenTokenIsValid() throws Exception {
        EvaluationReport report = new EvaluationReport("1.0", "v1", "2026-09-08T00:00:00Z",
                Map.of(), List.of(5), 1, 1, 0, Map.of("caseSuccessRate", 1.0),
                Map.of("caseSuccessRate", 1), List.of(), new EvaluationReport.GateResult(true, List.of()));
        when(evaluationEndpointService.run(any())).thenReturn(
                new EvaluationRunResponse(report, List.of(), "# RAG 评测报告"));

        mockMvc.perform(post("/internal/evaluation/rag/run")
                        .header(EvaluationEndpointController.TOKEN_HEADER, "evaluation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report.datasetVersion").value("v1"))
                .andExpect(jsonPath("$.data.report.gate.passed").value(true));

        verify(evaluationEndpointService).run(any());
    }

    @Test
    void shouldReturnBadRequestForInvalidEvaluationParameters() throws Exception {
        when(evaluationEndpointService.run(any()))
                .thenThrow(new IllegalArgumentException("datasetVersion 格式非法"));

        mockMvc.perform(post("/internal/evaluation/rag/run")
                        .header(EvaluationEndpointController.TOKEN_HEADER, "evaluation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"../v1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("datasetVersion 格式非法"));
    }
}
