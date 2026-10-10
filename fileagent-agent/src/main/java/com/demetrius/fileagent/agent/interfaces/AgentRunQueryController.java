package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Agent Run 查询接口：运行快照与取消。错误统一走全局 JSON 处理器（ApiResult）。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Agent")
public class AgentRunQueryController {

    private final AgentRuntimePort agentRuntimePort;
    private final AgentProperties properties;

    @GetMapping("/api/agent-runs/{runId}")
    public ApiResult<AgentRunSnapshot> snapshot(@PathVariable String runId) {
        ensureEnabled();
        return ApiResult.ok(agentRuntimePort.snapshot(runId));
    }

    @PostMapping("/api/agent-runs/{runId}/cancel")
    public ApiResult<AgentRunSnapshot> cancel(@PathVariable String runId) {
        ensureEnabled();
        return ApiResult.ok(agentRuntimePort.cancel(runId));
    }

    /** 返回会话中尚未终态的 Run；没有活动 Run 时 data 为 null。 */
    @GetMapping("/api/sessions/{sessionId}/agent-runs/active")
    public ApiResult<AgentRunSnapshot> active(@PathVariable Long sessionId) {
        ensureEnabled();
        return ApiResult.ok(agentRuntimePort.active(sessionId));
    }

    /** 使用 Last-Event-ID 回放有限窗口中的事件，并继续订阅本进程内的新事件。 */
    @GetMapping(value = "/api/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentRunEvent>> events(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        ensureEnabled();
        return agentRuntimePort.events(runId, lastEventId)
                .map(event -> ServerSentEvent.<AgentRunEvent>builder()
                        .id(event.eventId())
                        .event(event.type())
                        .data(event)
                        .build());
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new BizException(403, "AGENT_FEATURE_DISABLED: Agent 模式未启用");
        }
    }
}
