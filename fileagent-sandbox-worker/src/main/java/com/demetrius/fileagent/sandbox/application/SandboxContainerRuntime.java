package com.demetrius.fileagent.sandbox.application;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;

/**
 * 独立容器运行时边界，所有 Python 代码只能通过受限容器而不是 Worker JVM 执行。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public interface SandboxContainerRuntime {

    /** 执行单次隔离 Python 请求并返回稳定结果。 */
    SandboxExecutionResponse execute(SandboxExecutionRequest request);
}
