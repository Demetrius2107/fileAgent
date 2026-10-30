package com.demetrius.fileagent.api.dto.sandbox;

/**
 * 主应用提交给本地 Sandbox Worker 的固定执行请求，不承载镜像、网络或资源等运行控制项。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record SandboxExecutionRequest(
        String executionId,
        String code,
        String inputJson) {
}
