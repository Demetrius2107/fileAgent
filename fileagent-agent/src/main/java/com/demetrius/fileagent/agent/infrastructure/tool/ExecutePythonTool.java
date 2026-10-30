package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.port.PythonSandboxExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@code execute_python} 运行级工具：模型只能提交代码和 JSON 输入，所有 Sandbox 运行边界由服务端固定。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public final class ExecutePythonTool extends RunScopedActionTool {

    static final String TOOL_NAME = PythonSandboxActionDefinition.TOOL_NAME;

    private static final int MAX_CODE_BYTES = 20 * 1024;
    private static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    private static final int MAX_ARGUMENT_DEPTH = 8;
    private static final int MAX_ARGUMENT_FIELDS = 64;
    static final int MAX_PARAMETER_PREVIEW_CHARACTERS = 4_000;
    private static final int MAX_RESULT_CHARACTERS = 4_000;
    private static final int MAX_COMPLETION_NOTICE_CHARACTERS = 512;
    private static final String PARAMETER_PREVIEW_TRUNCATED_SUFFIX = "\n[审批预览已截断；完整内容由指纹绑定]";
    private static final Set<String> INPUT_FIELDS = Set.of("code", "input");

    private final String actorId;
    private final Set<String> authorizedResourceScopes;
    private final PythonSandboxExecutionPort sandboxExecutionPort;
    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;
    private final ActionDefinitionView definition;
    private final ConcurrentMap<String, CompletionNotice> completionNotices = new ConcurrentHashMap<>();

    ExecutePythonTool(String runId,
                      String actorId,
                      Set<String> authorizedResourceScopes,
                      PythonSandboxExecutionPort sandboxExecutionPort,
                      ObjectMapper objectMapper,
                      EnvelopeCrypto envelopeCrypto,
                      ActionDefinitionView definition) {
        super(runId, ToolBase.builder()
                        .name(TOOL_NAME)
                        .description("在隔离环境中执行 Python 脚本。仅提交 code 与 input JSON；无网络、只读文件系统，必须等待用户审批。")
                        .inputSchema(inputSchema())
                        .readOnly(false)
                        .concurrencySafe(false),
                new AgentActionPolicy(Set.of(TOOL_NAME)));
        this.actorId = requireText(actorId, "actorId");
        this.authorizedResourceScopes = Set.copyOf(
                authorizedResourceScopes == null ? Set.of() : authorizedResourceScopes);
        this.sandboxExecutionPort = Objects.requireNonNull(sandboxExecutionPort, "sandboxExecutionPort 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto, "envelopeCrypto 不能为空");
        this.definition = requireDefinition(definition);
    }

    @Override
    protected void validateSchema(Map<String, Object> input) {
        if (!input.keySet().equals(INPUT_FIELDS)) {
            throw new IllegalArgumentException("Python Sandbox 只能包含 code 和 input");
        }
        String code = stringValue(input.get("code"), "code");
        if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new IllegalArgumentException("Python Sandbox code 超过 20 KB");
        }
        objectValue(input.get("input"), "input");
    }

    @Override
    protected AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId) {
        ActionDefinitionView currentDefinition = requireDefinition(sandboxExecutionPort.definition());
        if (!sameDefinition(definition, currentDefinition)) {
            throw new IllegalStateException("Python Sandbox 动作定义绑定已变化");
        }
        String code = stringValue(input.get("code"), "code");
        Map<String, Object> sandboxInput = objectValue(input.get("input"), "input");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("code", code);
        arguments.put("input", sandboxInput);
        CanonicalArguments canonical = canonicalize(arguments);
        String operationFingerprint = envelopeCrypto.hmac(String.join("\n",
                "action-operation-fingerprint-v1", runId(), TOOL_NAME,
                definition.definitionVersion(), definition.endpointRevision(), canonical.json()));
        String operationId = "op_" + envelopeCrypto.hmac(
                "action-operation-id-v1\n" + operationFingerprint);

        return new AgentActionCandidate(
                runId(), toolCallId, TOOL_NAME, definition.toolVersion(), definition.definitionVersion(),
                definition.endpointRevision(), definition.endpointConfigHash(), definition.policyVersion(),
                definition.riskLevel(), definition.resourceScope(), authorizedResourceScopes,
                Map.copyOf(arguments), definition.externalizesModelFreeText(), canonical.json(), canonical.hmac(),
                operationId, definition.displayName() + "：" + definition.safeSummary(),
                definition.expectedImpact(), parameterPreview(canonical.json()));
    }

    @Override
    protected ToolResultBlock executeVerified(AgentActionCandidate candidate) {
        String executionKey = envelopeCrypto.hmac(String.join("\n",
                "action-execution-key-v1", candidate.runId(), candidate.toolCallId(),
                candidate.toolDefinitionVersion()));
        String operationFingerprint = envelopeCrypto.hmac(String.join("\n",
                "action-operation-fingerprint-v1", candidate.runId(), candidate.toolName(),
                candidate.toolDefinitionVersion(), candidate.endpointRevision(), candidate.canonicalArguments()));
        ActionExecutionView result = sandboxExecutionPort.execute(new ActionExecutionCommand(
                candidate.runId(), candidate.toolCallId(), candidate.toolName(), candidate.toolVersion(),
                PythonSandboxActionDefinition.ACTION_ID, candidate.toolDefinitionVersion(),
                candidate.endpointRevision(), candidate.endpointConfigHash(), candidate.policyVersion(),
                executionKey, operationFingerprint, candidate.operationId(), candidate.canonicalArguments(),
                candidate.canonicalInputHmac(), actorId, candidate.authorizedResourceScopes(),
                candidate.resourceScope()));
        String rendered = truncate(renderResult(result), MAX_RESULT_CHARACTERS);
        completionNotices.put(candidate.toolCallId(), new CompletionNotice(
                result.status().name(), truncate(renderCompletionNotice(result), MAX_COMPLETION_NOTICE_CHARACTERS)));
        return ToolResultBlock.text(rendered);
    }

    @Override
    public CompletionNotice takeCompletionNotice(String toolCallId) {
        return toolCallId == null ? null : completionNotices.remove(toolCallId);
    }

    private CanonicalArguments canonicalize(Map<String, Object> arguments) {
        try {
            JsonNode input = objectMapper.valueToTree(arguments);
            FieldCounter counter = new FieldCounter();
            JsonNode sorted = sort(input, 1, counter);
            String json = objectMapper.writeValueAsString(sorted);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
                throw new IllegalArgumentException("Python Sandbox 参数大小超过 64 KB");
            }
            return new CanonicalArguments(json, envelopeCrypto.hmac(json));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Python Sandbox 参数无法规范化", e);
        }
    }

    private JsonNode sort(JsonNode node, int depth, FieldCounter counter) {
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw new IllegalArgumentException("Python Sandbox 参数嵌套深度超过 " + MAX_ARGUMENT_DEPTH);
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        if (++counter.fields > MAX_ARGUMENT_FIELDS) {
                            throw new IllegalArgumentException("Python Sandbox 参数字段数量超过 " + MAX_ARGUMENT_FIELDS);
                        }
                        sorted.set(entry.getKey(), sort(entry.getValue(), depth + 1, counter));
                    });
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            node.forEach(value -> sorted.add(sort(value, depth + 1, counter)));
            return sorted;
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("Python Sandbox 参数包含非有限数字");
        }
        return node.deepCopy();
    }

    private static ActionDefinitionView requireDefinition(ActionDefinitionView definition) {
        if (definition == null || !PythonSandboxActionDefinition.ACTION_ID.equals(definition.actionId())
                || !PythonSandboxActionDefinition.TOOL_VERSION.equals(definition.toolVersion())
                || !PythonSandboxActionDefinition.DEFINITION_VERSION.equals(definition.definitionVersion())
                || !PythonSandboxActionDefinition.ENDPOINT_REVISION.equals(definition.endpointRevision())
                || !PythonSandboxActionDefinition.ENDPOINT_CONFIG_HASH.equals(definition.endpointConfigHash())
                || !PythonSandboxActionDefinition.POLICY_VERSION.equals(definition.policyVersion())
                || !PythonSandboxActionDefinition.RESOURCE_SCOPE.equals(definition.resourceScope())
                || definition.riskLevel() != ActionRiskLevel.HIGH_RISK_WRITE
                || definition.externalizesModelFreeText()
                || definition.argumentSchema() == null
                || !INPUT_FIELDS.equals(definition.modelWritableFields())) {
            throw new IllegalArgumentException("Python Sandbox 动作定义不完整");
        }
        return definition;
    }

    private static boolean sameDefinition(ActionDefinitionView expected, ActionDefinitionView actual) {
        return expected.actionId().equals(actual.actionId())
                && expected.toolVersion().equals(actual.toolVersion())
                && expected.definitionVersion().equals(actual.definitionVersion())
                && expected.endpointRevision().equals(actual.endpointRevision())
                && expected.endpointConfigHash().equals(actual.endpointConfigHash())
                && expected.policyVersion().equals(actual.policyVersion())
                && expected.resourceScope().equals(actual.resourceScope())
                && expected.riskLevel() == actual.riskLevel();
    }

    private static String renderResult(ActionExecutionView result) {
        if (result == null || result.status() == null || isBlank(result.executionId())) {
            throw new IllegalStateException("Python Sandbox 执行结果不完整");
        }
        String identity = "executionId=" + result.executionId()
                + (isBlank(result.operationId()) ? "" : "，operationId=" + result.operationId());
        return switch (result.status()) {
            case SUCCEEDED -> "Python 脚本执行成功，" + identity + resultText(result.resultSummary());
            case FAILED -> "Python 脚本执行失败，" + identity + codeText(result);
            case OUTCOME_UNKNOWN -> "Python Sandbox 执行结果不确定，" + identity
                    + "。请人工核对，禁止自动重试。" + codeText(result);
            case CANCELLED -> "Python 脚本执行已取消，" + identity + codeText(result);
            case PENDING, EXECUTING -> "Python 脚本仍在处理，" + identity + "。请勿重复提交。";
        };
    }

    private static String renderCompletionNotice(ActionExecutionView result) {
        String identity = "executionId=" + result.executionId()
                + (isBlank(result.operationId()) ? "" : "，operationId=" + result.operationId());
        return switch (result.status()) {
            case SUCCEEDED -> "Python 脚本执行完成，" + identity;
            case FAILED -> "Python 脚本执行失败，" + identity + codeText(result);
            case OUTCOME_UNKNOWN -> "Python Sandbox 执行结果不确定，" + identity
                    + "。请人工核对，禁止自动重试。" + codeText(result);
            case CANCELLED -> "Python 脚本执行已取消，" + identity + codeText(result);
            case PENDING, EXECUTING -> "Python 脚本仍在处理，" + identity + "。请勿重复提交。";
        };
    }

    private static String codeText(ActionExecutionView result) {
        return isBlank(result.resultCode()) ? "" : "，结果码=" + result.resultCode();
    }

    private static String resultText(String summary) {
        return isBlank(summary) ? "" : "，结果=" + summary;
    }

    private static Map<String, Object> objectValue(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " 必须是 JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(field + " 字段名不能为空");
            }
            result.put(name, item);
        });
        return Map.copyOf(result);
    }

    private static String stringValue(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return text.strip();
    }

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value;
        }
        return value.substring(0, maxCharacters);
    }

    private static String parameterPreview(String canonicalArguments) {
        if (canonicalArguments.length() <= MAX_PARAMETER_PREVIEW_CHARACTERS) {
            return canonicalArguments;
        }
        int contentLength = MAX_PARAMETER_PREVIEW_CHARACTERS - PARAMETER_PREVIEW_TRUNCATED_SUFFIX.length();
        return canonicalArguments.substring(0, contentLength) + PARAMETER_PREVIEW_TRUNCATED_SUFFIX;
    }

    private static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "code", Map.of(
                                "type", "string",
                                "maxLength", MAX_CODE_BYTES,
                                "description", "在隔离环境中执行的 Python 代码"),
                        "input", Map.of(
                                "type", "object",
                                "description", "提供给不可变 input_data 变量的 JSON object")),
                "required", List.of("code", "input"));
    }

    private record CanonicalArguments(String json, String hmac) {
    }

    private static final class FieldCounter {
        private int fields;
    }
}

/**
 * Sandbox 开关开启时为每个可信 Run 创建独立 Python 工具实例，未授权时不向模型暴露。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@ConditionalOnProperty(prefix = "fileagent.sandbox", name = "enabled", havingValue = "true")
final class ExecutePythonToolFactory implements AgentActionToolFactory {

    private static final String LOCAL_OPERATOR = "local-operator";

    private final AgentProperties properties;
    private final PythonSandboxExecutionPort sandboxExecutionPort;
    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;

    ExecutePythonToolFactory(AgentProperties properties,
                             PythonSandboxExecutionPort sandboxExecutionPort,
                             ObjectMapper objectMapper,
                             EnvelopeCrypto envelopeCrypto) {
        this.properties = properties;
        this.sandboxExecutionPort = sandboxExecutionPort;
        this.objectMapper = objectMapper;
        this.envelopeCrypto = envelopeCrypto;
    }

    @Override
    public boolean available(AgentRunCommand command) {
        ActionDefinitionView definition = sandboxExecutionPort.definition();
        return definition != null
                && properties.getAuthorizedActionResourceScopes().contains(definition.resourceScope());
    }

    @Override
    public RunScopedActionTool create(AgentRunCommand command) {
        if (command == null || command.runId() == null || command.runId().isBlank()) {
            throw new IllegalArgumentException("Agent Run 命令不能为空");
        }
        return new ExecutePythonTool(command.runId(), LOCAL_OPERATOR,
                properties.getAuthorizedActionResourceScopes(), sandboxExecutionPort,
                objectMapper, envelopeCrypto, sandboxExecutionPort.definition());
    }
}
