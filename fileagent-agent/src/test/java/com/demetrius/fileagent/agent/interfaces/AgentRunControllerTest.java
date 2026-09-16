package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.application.AgentRunAppService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void startShouldWriteSseFailedEventWhenSessionMissing() throws Exception {
        when(agentRunAppService.run(eq(1L), any(), isNull()))
                .thenThrow(new BizException(404, "会话不存在"));

        mockMvc.perform(post("/api/sessions/1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("event:run.failed")));
    }
}
