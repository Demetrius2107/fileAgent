package com.demetrius.fileagent.api.dto.action;

import java.util.Set;

/**
 * 服务端生成的动作执行命令，绑定 ToolCall、定义版本、幂等标识和可信授权上下文。
 *
 * @author raosaijie
 */
public record ActionExecutionCommand(
        String runId,
        String toolCallId,
        String toolName,
        String toolVersion,
        String actionId,
        String definitionVersion,
        String endpointRevision,
        String endpointConfigHash,
        String policyVersion,
        String executionKey,
        String operationFingerprint,
        String operationId,
        String canonicalArguments,
        String canonicalInputHmac,
        String actorId,
        Set<String> authorizedResourceScopes,
        String resourceScope) {

    public ActionExecutionCommand {
        authorizedResourceScopes = Set.copyOf(
                authorizedResourceScopes == null ? Set.of() : authorizedResourceScopes);
    }
}
