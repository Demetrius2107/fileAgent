package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.application.AgentApprovalWorkflowService;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批 SSE 接口的同源保护和同步准备验证。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class AgentApprovalControllerTest {

    private final AgentApprovalWorkflowService workflowService = mock(AgentApprovalWorkflowService.class);
    private final AgentRuntimePort agentRuntimePort = mock(AgentRuntimePort.class);
    private final AgentProperties properties = mock(AgentProperties.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isActionsEnabled()).thenReturn(true);
        AgentApprovalController controller = new AgentApprovalController(
                workflowService, agentRuntimePort, properties, mock(Tracer.class), new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldDecideSynchronouslyThenReturnRunEventStream() throws Exception {
        AgentApprovalView approval = approval();
        when(workflowService.decide(eq("run-1"), eq("approval-1"), any(AgentApprovalDecision.class)))
                .thenReturn(approval);
        when(agentRuntimePort.events("run-1", "event-0"))
                .thenReturn(Flux.just(AgentRunEvent.approvalResolved("run-1", approval)
                        .withMetadata("event-1", 2L)));

        mockMvc.perform(approvalRequest("run-1", "approval-1")
                        .header("Last-Event-ID", "event-0"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Agent-Run-Id", "run-1"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id:event-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:approval.resolved")));

        verify(workflowService).decide(eq("run-1"), eq("approval-1"), any(AgentApprovalDecision.class));
        verify(agentRuntimePort).events("run-1", "event-0");
    }

    @Test
    void shouldRejectMissingOriginBeforeChangingApproval() throws Exception {
        mockMvc.perform(post("/api/agent-runs/run-1/approvals/approval-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Host", "localhost")
                        .header("X-FileAgent-Client", "web")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")));

        verifyNoInteractions(workflowService, agentRuntimePort);
    }

    @Test
    void shouldRejectMismatchedOriginAndHostBeforeChangingApproval() throws Exception {
        mockMvc.perform(post("/api/agent-runs/run-1/approvals/approval-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Origin", "http://evil.example")
                        .header("Host", "localhost")
                        .header("X-FileAgent-Client", "web")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")));

        verifyNoInteractions(workflowService, agentRuntimePort);
    }

    @Test
    void shouldKeepConflictAsSseBusinessError() throws Exception {
        when(workflowService.decide(eq("run-1"), eq("approval-1"), any(AgentApprovalDecision.class)))
                .thenThrow(new BizException(409, "审批已经处理为: DENIED"));

        mockMvc.perform(approvalRequest("run-1", "approval-1"))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")));
    }

    @Test
    void shouldRejectApprovalWhenActionsFeatureIsDisabledButNotCallWorkflow() throws Exception {
        when(properties.isActionsEnabled()).thenReturn(false);

        mockMvc.perform(approvalRequest("run-1", "approval-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AGENT_ACTIONS_DISABLED")));

        verifyNoInteractions(workflowService, agentRuntimePort);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approvalRequest(
            String runId, String approvalId) {
        return post("/api/agent-runs/{runId}/approvals/{approvalId}", runId, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Origin", "http://localhost")
                .header("Host", "localhost")
                .header("X-FileAgent-Client", "web")
                .content("{\"decision\":\"APPROVE\",\"comment\":\"确认执行\"}");
    }

    private AgentApprovalView approval() {
        return new AgentApprovalView("approval-1", "managed_http_action", ActionRiskLevel.HIGH_RISK_WRITE,
                "执行测试动作", "resource:demo", "影响一条演示记录", "{\"payload\":\"***\"}",
                "input-hmac", Instant.parse("2026-10-12T08:05:00Z"), ApprovalStatus.PENDING);
    }
}
