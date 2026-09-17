package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentRunQueryControllerTest {

    @Mock
    private AgentRuntimePort agentRuntimePort;
    @Mock
    private AgentProperties properties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        AgentRunQueryController controller = new AgentRunQueryController(agentRuntimePort, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void snapshotShouldReturnApiResultWithSnapshot() throws Exception {
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
                "run-1", 1L, AgentRunStatus.RUNNING, Instant.now(), null, 1, 1, null, null, "trace-1");
        when(agentRuntimePort.snapshot("run-1")).thenReturn(snapshot);

        mockMvc.perform(get("/api/agent-runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void snapshotShouldReturn404WhenRunMissing() throws Exception {
        when(agentRuntimePort.snapshot("missing")).thenThrow(new BizException(404, "Agent Run 不存在: missing"));

        mockMvc.perform(get("/api/agent-runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void cancelShouldReturnApiResultWithCancelledStatus() throws Exception {
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
                "run-1", 1L, AgentRunStatus.CANCELLED, Instant.now(), Instant.now(), 0, 0, null, null, "trace-1");
        when(agentRuntimePort.cancel("run-1")).thenReturn(snapshot);

        mockMvc.perform(post("/api/agent-runs/run-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void shouldReturn403WhenFeatureDisabled() throws Exception {
        when(properties.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/agent-runs/run-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(agentRuntimePort);
    }
}
