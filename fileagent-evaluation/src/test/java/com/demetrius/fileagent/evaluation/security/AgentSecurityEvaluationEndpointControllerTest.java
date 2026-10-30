package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.evaluation.EvaluationEndpointProperties;
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

/**
 * 内部模型攻击评测接口的令牌和受控输出契约。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@ExtendWith(MockitoExtension.class)
class AgentSecurityEvaluationEndpointControllerTest {

    @Mock
    private AgentSecurityEvaluationEndpointService endpointService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        EvaluationEndpointProperties properties = new EvaluationEndpointProperties();
        properties.setToken("evaluation-secret");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgentSecurityEvaluationEndpointController(endpointService, properties)).build();
    }

    @Test
    void shouldRejectRequestWithoutValidToken() throws Exception {
        mockMvc.perform(post("/internal/evaluation/agent-security/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(endpointService, never()).run(any());
    }

    @Test
    void shouldReturnOnlyControlledSecurityFactsWhenTokenIsValid() throws Exception {
        AgentSecurityEvaluationReport report = new AgentSecurityEvaluationReport("1.0", "phase3-model-attack-v1",
                "2026-10-27T00:00:00Z", 1, 1,
                new AgentSecurityEvaluationReport.Metrics(1, 0, 1, 0, 1), List.of(),
                new AgentSecurityEvaluationReport.GateResult(true, List.of(), List.of()));
        when(endpointService.run(any())).thenReturn(new AgentSecurityEvaluationRunResponse(report, List.of(),
                "# Phase 3 模型攻击评测报告"));

        mockMvc.perform(post("/internal/evaluation/agent-security/run")
                        .header(AgentSecurityEvaluationEndpointController.TOKEN_HEADER, "evaluation-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"phase3-model-attack-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.report.datasetVersion").value("phase3-model-attack-v1"))
                .andExpect(jsonPath("$.data.report.gate.passed").value(true))
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.answer").doesNotExist());

        verify(endpointService).run(any());
    }
}
