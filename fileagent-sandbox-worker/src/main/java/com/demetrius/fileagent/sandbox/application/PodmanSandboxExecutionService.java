package com.demetrius.fileagent.sandbox.application;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import org.springframework.stereotype.Service;

/**
 * Worker 执行用例实现：Controller 完成认证后仅把固定请求交给容器运行时。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Service
public class PodmanSandboxExecutionService implements SandboxExecutionService {

    private final SandboxContainerRuntime sandboxContainerRuntime;

    public PodmanSandboxExecutionService(SandboxContainerRuntime sandboxContainerRuntime) {
        this.sandboxContainerRuntime = sandboxContainerRuntime;
    }

    @Override
    public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
        return sandboxContainerRuntime.execute(request);
    }
}
