package com.demetrius.fileagent.api.dto.sandbox;

/**
 * Sandbox Worker 的受控执行结果，调用方只能依据稳定状态和错误码判断结果。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record SandboxExecutionResponse(
        String status,
        String stdout,
        String stderr,
        String errorCode) {
}
