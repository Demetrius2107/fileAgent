package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Run 查询接口：运行快照与取消。错误统一走全局 JSON 处理器（ApiResult）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent-runs")
@Tag(name = "Agent")
public class AgentRunQueryController {

    private final AgentRuntimePort agentRuntimePort;

    @GetMapping("/{runId}")
    public ApiResult<AgentRunSnapshot> snapshot(@PathVariable String runId) {
        return ApiResult.ok(agentRuntimePort.snapshot(runId));
    }

    @PostMapping("/{runId}/cancel")
    public ApiResult<AgentRunSnapshot> cancel(@PathVariable String runId) {
        return ApiResult.ok(agentRuntimePort.cancel(runId));
    }
}
