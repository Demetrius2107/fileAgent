package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.action.infrastructure.http.CanonicalActionArguments;
import com.demetrius.fileagent.action.infrastructure.sandbox.SandboxClient;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.port.PythonSandboxExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Python Sandbox 动作应用服务：复用副作用账本，但 Worker 通信不确定时绝不重放脚本。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Service
@ConditionalOnProperty(prefix = "fileagent.sandbox", name = "enabled", havingValue = "true")
public class PythonSandboxActionExecutionService implements PythonSandboxExecutionPort {

    private static final int MAX_CODE_BYTES = 20 * 1024;
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final int MAX_RESULT_SUMMARY_CHARS = 4_000;
    private static final Set<String> ARGUMENT_FIELDS = Set.of("code", "input");

    private final ActionToolExecutionRepository repository;
    private final SandboxClient sandboxClient;
    private final EnvelopeCrypto envelopeCrypto;
    private final ObjectMapper objectMapper;

    public PythonSandboxActionExecutionService(ActionToolExecutionRepository repository,
                                               SandboxClient sandboxClient,
                                               EnvelopeCrypto envelopeCrypto,
                                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.sandboxClient = sandboxClient;
        this.envelopeCrypto = envelopeCrypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public ActionDefinitionView definition() {
        return PythonSandboxActionDefinition.definition();
    }

    @Override
    public ActionExecutionView execute(ActionExecutionCommand command) {
        ValidatedCommand validated = validateCommand(command);
        Optional<ActionToolExecutionEntity> existing = findExisting(command);
        if (existing.isPresent()) {
            return toView(existing.get(), null);
        }

        ActionToolExecutionEntity pending = ActionToolExecutionEntity.pending(
                UUID.randomUUID().toString(), command, Instant.now());
        try {
            pending = repository.save(pending);
        } catch (DataIntegrityViolationException duplicate) {
            return toView(findExisting(command).orElseThrow(() -> duplicate), null);
        }
        if (repository.beginPending(pending.getExecutionId(), pending.getVersion(), Instant.now()) != 1) {
            return toView(repository.findByExecutionId(pending.getExecutionId())
                    .orElseGet(() -> findExisting(command).orElseThrow()), null);
        }
        ActionToolExecutionEntity executing = repository.findByExecutionId(pending.getExecutionId())
                .orElseThrow(() -> new IllegalStateException("Python Sandbox 账本抢占后不存在"));

        SandboxExecutionResponse outcome;
        try {
            outcome = sandboxClient.execute(executing.getExecutionId(), validated.code(), validated.inputJson());
        } catch (RuntimeException unexpected) {
            return persistUnknown(executing, "SANDBOX_WORKER_PROTOCOL_ERROR");
        }
        try {
            applyOutcome(executing, outcome);
            ActionToolExecutionEntity saved = repository.save(executing);
            return toView(saved, outcome != null && "SUCCEEDED".equals(outcome.status())
                    ? serializeResponse(outcome) : null);
        } catch (RuntimeException persistenceFailure) {
            return convergeAfterPersistenceFailure(executing.getExecutionId());
        }
    }

    @Override
    public ActionExecutionView findExecution(String executionId) {
        return toView(repository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Python Sandbox 执行记录不存在: " + executionId)), null);
    }

    private ValidatedCommand validateCommand(ActionExecutionCommand command) {
        if (command == null || !PythonSandboxActionDefinition.TOOL_NAME.equals(command.toolName())) {
            throw new IllegalArgumentException("Python Sandbox 执行命令工具不匹配");
        }
        ActionDefinitionView definition = definition();
        if (!equal(definition.actionId(), command.actionId())
                || !equal(definition.toolVersion(), command.toolVersion())
                || !equal(definition.definitionVersion(), command.definitionVersion())
                || !equal(definition.endpointRevision(), command.endpointRevision())
                || !equal(definition.endpointConfigHash(), command.endpointConfigHash())
                || !equal(definition.policyVersion(), command.policyVersion())
                || !equal(definition.resourceScope(), command.resourceScope())) {
            throw new IllegalArgumentException("Python Sandbox 动作定义绑定已变化");
        }
        if (command.authorizedResourceScopes() == null
                || !command.authorizedResourceScopes().contains(command.resourceScope())) {
            throw new IllegalArgumentException("Python Sandbox 资源未授权");
        }
        if (command.actorId() == null || command.actorId().isBlank()) {
            throw new IllegalArgumentException("Python Sandbox 执行身份不能为空");
        }
        JsonNode arguments = parseArguments(command.canonicalArguments());
        CanonicalActionArguments canonical = CanonicalActionArguments.from(arguments, objectMapper, envelopeCrypto);
        if (!equal(canonical.json(), command.canonicalArguments())
                || !secureEqual(canonical.hmac(), command.canonicalInputHmac())) {
            throw new IllegalArgumentException("Python Sandbox 参数绑定校验失败");
        }
        String code = arguments.get("code").stringValue();
        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(arguments.get("input"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Python Sandbox input 无法序列化", e);
        }
        if (code.isBlank()) {
            throw new IllegalArgumentException("Python Sandbox code 不能为空");
        }
        if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new IllegalArgumentException("Python Sandbox code 超过 20 KB");
        }
        if (inputJson.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Python Sandbox input 超过 64 KB");
        }
        return new ValidatedCommand(code, inputJson);
    }

    private JsonNode parseArguments(String canonicalArguments) {
        try {
            JsonNode arguments = objectMapper.readTree(canonicalArguments);
            if (arguments == null || !arguments.isObject()
                    || !arguments.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet())
                    .equals(ARGUMENT_FIELDS)
                    || !arguments.get("code").isString()
                    || !arguments.get("input").isObject()) {
                throw new IllegalArgumentException("Python Sandbox 参数只能包含 code 和 input object");
            }
            return arguments;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Python Sandbox 参数不是合法 JSON object", e);
        }
    }

    private Optional<ActionToolExecutionEntity> findExisting(ActionExecutionCommand command) {
        Optional<ActionToolExecutionEntity> byExecutionKey = repository.findByExecutionKey(command.executionKey());
        if (byExecutionKey.isPresent()) {
            requireSameToolCallBinding(byExecutionKey.get(), command);
            return byExecutionKey;
        }
        Optional<ActionToolExecutionEntity> byFingerprint = repository.findByRunIdAndOperationFingerprint(
                command.runId(), command.operationFingerprint());
        if (byFingerprint.isPresent()) {
            requireSameSemanticBinding(byFingerprint.get(), command);
            return byFingerprint;
        }
        Optional<ActionToolExecutionEntity> byOperationId = repository.findByOperationId(command.operationId());
        byOperationId.ifPresent(execution -> requireSameSemanticBinding(execution, command));
        return byOperationId;
    }

    private void applyOutcome(ActionToolExecutionEntity execution, SandboxExecutionResponse outcome) {
        if (outcome == null || !"SUCCEEDED".equals(outcome.status()) && !"FAILED".equals(outcome.status())) {
            execution.markOutcomeUnknown("SANDBOX_WORKER_PROTOCOL_ERROR", Instant.now());
            return;
        }
        if ("SUCCEEDED".equals(outcome.status())) {
            String body = serializeResponse(outcome);
            execution.markSucceeded("PYTHON_EXECUTION_SUCCEEDED", envelopeCrypto.encrypt(body, aad(execution)),
                    envelopeCrypto.hmac(body), null, Instant.now());
            return;
        }
        String resultCode = stableResultCode(outcome.errorCode());
        if (isUncertainWorkerFailure(resultCode)) {
            execution.markOutcomeUnknown(resultCode, Instant.now());
            return;
        }
        String body = serializeResponse(outcome);
        execution.markFailed(resultCode, envelopeCrypto.encrypt(body, aad(execution)),
                envelopeCrypto.hmac(body), null, Instant.now());
    }

    private ActionExecutionView convergeAfterPersistenceFailure(String executionId) {
        Optional<ActionToolExecutionEntity> current = repository.findByExecutionId(executionId);
        if (current.isPresent() && current.get().getStatus() != ActionExecutionStatus.EXECUTING) {
            return toView(current.get(), null);
        }
        if (current.isPresent()) {
            return persistUnknown(current.get(), "PYTHON_RESULT_PERSISTENCE_UNKNOWN");
        }
        return unknownView(executionId, null, "PYTHON_RESULT_PERSISTENCE_UNKNOWN", null);
    }

    private ActionExecutionView persistUnknown(ActionToolExecutionEntity execution, String code) {
        try {
            if (execution.getStatus() == ActionExecutionStatus.EXECUTING) {
                execution.markOutcomeUnknown(code, Instant.now());
                execution = repository.save(execution);
            }
            return toView(execution, null);
        } catch (RuntimeException ignored) {
            return unknownView(execution.getExecutionId(), execution.getOperationId(), code,
                    execution.getStartedAt());
        }
    }

    private ActionExecutionView toView(ActionToolExecutionEntity execution, String plaintextResult) {
        if (execution.getStatus() == ActionExecutionStatus.OUTCOME_UNKNOWN) {
            return unknownView(execution.getExecutionId(), execution.getOperationId(), execution.getResultCode(),
                    execution.getStartedAt());
        }
        String summary = null;
        if (execution.getStatus() == ActionExecutionStatus.SUCCEEDED) {
            String result = plaintextResult;
            if (result == null && execution.getResultCiphertext() != null) {
                try {
                    result = envelopeCrypto.decrypt(execution.getResultCiphertext(), aad(execution));
                    if (!secureEqual(envelopeCrypto.hmac(result), execution.getResultHash())) {
                        return failedIntegrityView(execution);
                    }
                } catch (RuntimeException decryptionFailure) {
                    return failedDecryptionView(execution);
                }
            }
            summary = renderSuccessfulOutput(result);
        } else if (execution.getStatus() == ActionExecutionStatus.FAILED) {
            summary = "Python 脚本执行失败，结果码=" + stableResultCode(execution.getResultCode());
        } else if (execution.getStatus() == ActionExecutionStatus.CANCELLED) {
            summary = "Python 脚本执行已取消。";
        } else if (execution.getStatus() == ActionExecutionStatus.PENDING
                || execution.getStatus() == ActionExecutionStatus.EXECUTING) {
            summary = "Python 脚本仍在处理，请勿重复提交。";
        }
        return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(), execution.getStatus(),
                execution.getResultCode(), truncate(summary), false, execution.getStartedAt(), execution.getEndedAt());
    }

    private String renderSuccessfulOutput(String serializedResult) {
        if (serializedResult == null || serializedResult.isBlank()) {
            return "Python 脚本执行成功（无标准输出）。";
        }
        try {
            JsonNode result = objectMapper.readTree(serializedResult);
            JsonNode stdout = result == null ? null : result.get("stdout");
            if (stdout == null || !stdout.isString() || stdout.stringValue().isBlank()) {
                return "Python 脚本执行成功（无标准输出）。";
            }
            return "Python 脚本执行成功，标准输出：\n" + stdout.stringValue();
        } catch (Exception e) {
            return "Python 脚本执行成功，但结果无法解析。";
        }
    }

    private String serializeResponse(SandboxExecutionResponse response) {
        try {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("stdout", response.stdout() == null ? "" : response.stdout());
            value.put("stderr", response.stderr() == null ? "" : response.stderr());
            value.put("errorCode", response.errorCode() == null ? "" : response.errorCode());
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Python Sandbox 结果无法序列化", e);
        }
    }

    private static boolean isUncertainWorkerFailure(String resultCode) {
        return "SANDBOX_WORKER_UNAVAILABLE".equals(resultCode)
                || "SANDBOX_WORKER_PROTOCOL_ERROR".equals(resultCode)
                || "SANDBOX_WORKER_RESPONSE_TOO_LARGE".equals(resultCode)
                || "SANDBOX_WORKER_REDIRECT_REJECTED".equals(resultCode)
                || "SANDBOX_TIMEOUT".equals(resultCode)
                || "SANDBOX_RUNTIME_FAILURE".equals(resultCode)
                || "SANDBOX_RUNTIME_INTERRUPTED".equals(resultCode)
                || "SANDBOX_INVALID_RUNNER_RESPONSE".equals(resultCode)
                || "SANDBOX_OUTPUT_LIMIT_EXCEEDED".equals(resultCode)
                || "SANDBOX_OUTPUT_DRAIN_TIMEOUT".equals(resultCode)
                || "SANDBOX_TERMINATION_UNCONFIRMED".equals(resultCode)
                || resultCode.matches("SANDBOX_WORKER_HTTP_5\\d\\d");
    }

    private static String stableResultCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,128}")
                ? code : "PYTHON_SANDBOX_INVALID_RESPONSE";
    }

    private static ActionExecutionView unknownView(String executionId, String operationId,
                                                   String resultCode, Instant startedAt) {
        return new ActionExecutionView(executionId, operationId, ActionExecutionStatus.OUTCOME_UNKNOWN,
                stableResultCode(resultCode), "Python Sandbox 执行结果不确定，可能已经开始运行，请使用 executionId 人工核对，禁止自动重试。",
                false, startedAt, Instant.now());
    }

    private static ActionExecutionView failedIntegrityView(ActionToolExecutionEntity execution) {
        return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(),
                ActionExecutionStatus.FAILED, "PYTHON_RESULT_INTEGRITY_FAILED", "Python Sandbox 结果完整性校验失败。",
                false, execution.getStartedAt(), execution.getEndedAt());
    }

    private static ActionExecutionView failedDecryptionView(ActionToolExecutionEntity execution) {
        return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(),
                ActionExecutionStatus.FAILED, "PYTHON_RESULT_DECRYPT_FAILED", "Python Sandbox 结果无法安全恢复。",
                false, execution.getStartedAt(), execution.getEndedAt());
    }

    private static void requireSameToolCallBinding(ActionToolExecutionEntity execution,
                                                   ActionExecutionCommand command) {
        requireSameSemanticBinding(execution, command);
        if (!equal(execution.getToolCallId(), command.toolCallId())
                || !equal(execution.getOperationId(), command.operationId())) {
            throw new IllegalArgumentException("executionKey 已绑定到不同 Python ToolCall");
        }
    }

    private static void requireSameSemanticBinding(ActionToolExecutionEntity execution,
                                                   ActionExecutionCommand command) {
        if (!equal(execution.getRunId(), command.runId())
                || !equal(execution.getToolName(), command.toolName())
                || !equal(execution.getToolVersion(), command.toolVersion())
                || !equal(execution.getToolDefinitionVersion(), command.definitionVersion())
                || !equal(execution.getEndpointRevision(), command.endpointRevision())
                || !secureEqual(execution.getCanonicalRequestHmac(), command.canonicalInputHmac())) {
            throw new IllegalArgumentException("Python Sandbox 幂等记录与当前动作绑定冲突");
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_RESULT_SUMMARY_CHARS
                ? value : value.substring(0, MAX_RESULT_SUMMARY_CHARS);
    }

    private static String aad(ActionToolExecutionEntity execution) {
        return "action_tool_execution|" + execution.getExecutionId() + "|result_ciphertext|v1";
    }

    private static boolean equal(String left, String right) {
        return left != null && left.equals(right);
    }

    private static boolean secureEqual(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private record ValidatedCommand(String code, String inputJson) {
    }
}
