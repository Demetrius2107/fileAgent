package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.action.infrastructure.http.CanonicalActionArguments;
import com.demetrius.fileagent.action.infrastructure.http.RegisteredHttpActionRegistry;
import com.demetrius.fileagent.action.infrastructure.http.RegisteredHttpExecutor;
import com.demetrius.fileagent.api.dto.action.ActionCancelResult;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.enums.ActionCancelStatus;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.port.ActionExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 注册 HTTP 动作应用服务：复核可信绑定，通过幂等账本执行并加密保存可恢复结果。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Service
@ConditionalOnProperty(prefix = "fileagent.action.http", name = "enabled", havingValue = "true")
public class ActionExecutionService implements ActionExecutionPort {

    private static final String TOOL_NAME = "call_registered_http_api";
    private static final int MAX_RESULT_SUMMARY_CHARS = 4_000;
    private static final Set<String> SENSITIVE_RESULT_KEYS = Set.of(
            "authorization", "credential", "credentials", "token", "accesstoken",
            "refreshtoken", "apikey", "password", "secret");

    private final RegisteredHttpActionRegistry registry;
    private final ActionToolExecutionRepository repository;
    private final RegisteredHttpExecutor executor;
    private final EnvelopeCrypto envelopeCrypto;
    private final ObjectMapper objectMapper;

    public ActionExecutionService(RegisteredHttpActionRegistry registry,
                                  ActionToolExecutionRepository repository,
                                  RegisteredHttpExecutor executor,
                                  EnvelopeCrypto envelopeCrypto,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.repository = repository;
        this.executor = executor;
        this.envelopeCrypto = envelopeCrypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public ActionDefinitionView resolveDefinition(String actionId) {
        return registry.resolveDefinition(actionId);
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
                .orElseThrow(() -> new IllegalStateException("动作执行账本抢占后不存在"));

        RegisteredHttpExecutor.HttpExecutionResult outcome;
        try {
            outcome = executor.execute(executing.getExecutionId(), command.actionId(),
                    command.operationId(), validated.arguments());
        } catch (RuntimeException unexpected) {
            return persistUnknown(executing, "HTTP_EXECUTOR_UNKNOWN");
        }
        try {
            applyOutcome(executing, outcome);
            ActionToolExecutionEntity saved = repository.save(executing);
            return toView(saved, outcome.status() == ActionExecutionStatus.SUCCEEDED
                    ? sanitizeSuccessfulResult(outcome.body()) : null);
        } catch (RuntimeException persistenceFailure) {
            return convergeAfterPersistenceFailure(executing.getExecutionId());
        }
    }

    @Override
    public ActionExecutionView findExecution(String executionId) {
        return toView(repository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalArgumentException("动作执行记录不存在: " + executionId)), null);
    }

    @Override
    public ActionCancelResult cancel(String executionId) {
        ActionToolExecutionEntity execution = repository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalArgumentException("动作执行记录不存在: " + executionId));
        if (execution.getStatus() == ActionExecutionStatus.CANCELLED) {
            return new ActionCancelResult(executionId, ActionCancelStatus.CANCELLED);
        }
        if (execution.getStatus() == ActionExecutionStatus.PENDING) {
            execution.markCancelled("HTTP_CANCELLED", Instant.now());
            repository.save(execution);
            return new ActionCancelResult(executionId, ActionCancelStatus.CANCELLED);
        }
        if (execution.getStatus() != ActionExecutionStatus.EXECUTING) {
            return new ActionCancelResult(executionId, ActionCancelStatus.OUTCOME_UNKNOWN);
        }
        ActionCancelStatus status = executor.cancel(executionId);
        if (status == ActionCancelStatus.CANCELLED) {
            execution.markCancelled("HTTP_CANCELLED", Instant.now());
        } else {
            status = ActionCancelStatus.OUTCOME_UNKNOWN;
            execution.markOutcomeUnknown("HTTP_OUTCOME_UNKNOWN", Instant.now());
        }
        repository.save(execution);
        return new ActionCancelResult(executionId, status);
    }

    private ValidatedCommand validateCommand(ActionExecutionCommand command) {
        if (command == null || !TOOL_NAME.equals(command.toolName())) {
            throw new IllegalArgumentException("动作执行命令工具不匹配");
        }
        ActionDefinitionView definition = registry.resolveDefinition(command.actionId());
        if (!equal(definition.toolVersion(), command.toolVersion())
                || !equal(definition.definitionVersion(), command.definitionVersion())
                || !equal(definition.endpointRevision(), command.endpointRevision())
                || !equal(definition.endpointConfigHash(), command.endpointConfigHash())
                || !equal(definition.policyVersion(), command.policyVersion())
                || !equal(definition.resourceScope(), command.resourceScope())) {
            throw new IllegalArgumentException("动作定义绑定已变化");
        }
        if (command.authorizedResourceScopes() == null
                || !command.authorizedResourceScopes().contains(command.resourceScope())) {
            throw new IllegalArgumentException("动作资源未授权");
        }
        if (command.actorId() == null || command.actorId().isBlank()) {
            throw new IllegalArgumentException("动作执行身份不能为空");
        }
        Map<String, Object> arguments = parseArguments(command.canonicalArguments());
        CanonicalActionArguments canonical = registry.validateArguments(command.actionId(), arguments);
        if (!equal(canonical.json(), command.canonicalArguments())
                || !secureEqual(canonical.hmac(), command.canonicalInputHmac())) {
            throw new IllegalArgumentException("动作参数绑定校验失败");
        }
        return new ValidatedCommand(arguments);
    }

    private Map<String, Object> parseArguments(String canonicalArguments) {
        try {
            return objectMapper.readValue(canonicalArguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("动作参数不是合法 JSON object", e);
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
        byFingerprint.ifPresent(execution -> requireSameSemanticBinding(execution, command));
        return byFingerprint;
    }

    private void applyOutcome(ActionToolExecutionEntity execution,
                              RegisteredHttpExecutor.HttpExecutionResult outcome) {
        Instant now = Instant.now();
        switch (outcome.status()) {
            case SUCCEEDED -> {
                String body = outcome.body() == null || outcome.body().isBlank() ? "{}" : outcome.body();
                execution.markSucceeded(outcome.resultCode(), envelopeCrypto.encrypt(body, aad(execution)),
                        envelopeCrypto.hmac(body), outcome.externalRequestId(), now);
            }
            case FAILED -> {
                String body = outcome.body();
                execution.markFailed(outcome.resultCode(),
                        body == null || body.isBlank() ? null : envelopeCrypto.encrypt(body, aad(execution)),
                        body == null || body.isBlank() ? null : envelopeCrypto.hmac(body),
                        outcome.externalRequestId(), now);
            }
            case OUTCOME_UNKNOWN -> execution.markOutcomeUnknown(outcome.resultCode(), now);
            case CANCELLED -> execution.markCancelled(outcome.resultCode(), now);
            default -> throw new IllegalStateException("HTTP 执行器返回了非终态: " + outcome.status());
        }
    }

    private ActionExecutionView convergeAfterPersistenceFailure(String executionId) {
        Optional<ActionToolExecutionEntity> current = repository.findByExecutionId(executionId);
        if (current.isPresent() && current.get().getStatus() != ActionExecutionStatus.EXECUTING) {
            return toView(current.get(), null);
        }
        if (current.isPresent()) {
            return persistUnknown(current.get(), "HTTP_RESULT_PERSISTENCE_UNKNOWN");
        }
        return unknownView(executionId, null, "HTTP_RESULT_PERSISTENCE_UNKNOWN", null);
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
        String summary = plaintextResult;
        if (execution.getStatus() == ActionExecutionStatus.FAILED) {
            summary = "外部接口调用失败：" + execution.getResultCode();
        } else if (summary == null && execution.getResultCiphertext() != null) {
            try {
                summary = envelopeCrypto.decrypt(execution.getResultCiphertext(), aad(execution));
                if (!secureEqual(envelopeCrypto.hmac(summary), execution.getResultHash())) {
                    return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(),
                            ActionExecutionStatus.FAILED, "ACTION_RESULT_INTEGRITY_FAILED",
                            "动作结果完整性校验失败。", false,
                            execution.getStartedAt(), execution.getEndedAt());
                }
                summary = sanitizeSuccessfulResult(summary);
            } catch (RuntimeException decryptionFailure) {
                return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(),
                        ActionExecutionStatus.FAILED, "ACTION_RESULT_DECRYPT_FAILED",
                        "动作结果无法安全恢复。", false,
                        execution.getStartedAt(), execution.getEndedAt());
            }
        }
        if (execution.getStatus() == ActionExecutionStatus.CANCELLED) {
            summary = "动作已取消，未确认产生外部副作用。";
        }
        return new ActionExecutionView(execution.getExecutionId(), execution.getOperationId(),
                execution.getStatus(), execution.getResultCode(), truncate(summary),
                recoverable(execution), execution.getStartedAt(), execution.getEndedAt());
    }

    private static void requireSameToolCallBinding(ActionToolExecutionEntity execution,
                                                   ActionExecutionCommand command) {
        requireSameSemanticBinding(execution, command);
        if (!equal(execution.getToolCallId(), command.toolCallId())
                || !equal(execution.getOperationId(), command.operationId())) {
            throw new IllegalArgumentException("executionKey 已绑定到不同 ToolCall");
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
            throw new IllegalArgumentException("幂等记录与当前动作绑定冲突");
        }
    }

    private String sanitizeSuccessfulResult(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            redactSensitiveFields(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception notJson) {
            return value;
        }
    }

    private static void redactSensitiveFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String normalized = entry.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
                if (SENSITIVE_RESULT_KEYS.contains(normalized)) {
                    node.asObject().put(entry.getKey(), "[REDACTED]");
                } else {
                    redactSensitiveFields(entry.getValue());
                }
            });
        } else if (node.isArray()) {
            node.forEach(ActionExecutionService::redactSensitiveFields);
        }
    }

    private static ActionExecutionView unknownView(String executionId, String operationId,
                                                   String resultCode, Instant startedAt) {
        return new ActionExecutionView(executionId, operationId, ActionExecutionStatus.OUTCOME_UNKNOWN,
                resultCode, "外部操作可能已执行，请使用 executionId 人工核对，禁止自动重试。",
                false, startedAt, Instant.now());
    }

    private static boolean recoverable(ActionToolExecutionEntity execution) {
        if (execution.getStatus() != ActionExecutionStatus.FAILED || execution.getResultCode() == null) {
            return false;
        }
        return execution.getResultCode().equals("HTTP_TRANSPORT_FAILED")
                || execution.getResultCode().matches("HTTP_5\\d\\d");
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

    private record ValidatedCommand(Map<String, Object> arguments) {
    }
}
