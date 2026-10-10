package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.application.AgentRunAppService;
import com.demetrius.fileagent.agent.application.AgentRunPreparation;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @Mock
    private AgentRunAppService agentRunAppService;
    @Mock
    private Tracer tracer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentRunController controller = new AgentRunController(agentRunAppService, tracer, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void startShouldWriteSseErrorEventWhenSessionMissing() throws Exception {
        when(agentRunAppService.prepare(eq(1L), any(), isNull()))
                .thenThrow(new BizException(404, "会话不存在"));

        mockMvc.perform(post("/api/sessions/1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("event:error")));
    }

    @Test
    void startShouldExposeRunIdAndSseEventIdAfterSynchronousPreparation() throws Exception {
        when(agentRunAppService.prepare(eq(1L), any(), isNull()))
                .thenReturn(new AgentRunPreparation("run-1", Flux.just(
                        AgentRunEvent.pending("run-1").withMetadata("event-1", 0L))));

        mockMvc.perform(post("/api/sessions/1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Agent-Run-Id", "run-1"))
                .andExpect(content().string(containsString("id:event-1")))
                .andExpect(content().string(containsString("event:run.status")));
    }
}
