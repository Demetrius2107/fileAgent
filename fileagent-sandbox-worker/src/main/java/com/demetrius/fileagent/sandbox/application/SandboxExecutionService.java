package com.demetrius.fileagent.sandbox.application;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;

/**
 * Sandbox Worker 的执行用例边界，Controller 不感知容器运行时实现。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@FunctionalInterface
public interface SandboxExecutionService {

    /**
     * 执行已经过认证和格式校验的固定请求。
     *
     * @param request 不含任何调用方可控运行参数的执行请求
     * @return 受控且可序列化的执行结果
     */
    SandboxExecutionResponse execute(SandboxExecutionRequest request);
}
