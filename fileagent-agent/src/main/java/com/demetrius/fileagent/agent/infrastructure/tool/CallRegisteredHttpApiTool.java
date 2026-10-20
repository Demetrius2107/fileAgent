package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.port.ActionExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
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

/**
 * {@code call_registered_http_api} 运行级工具：模型只选择预注册 Endpoint 并提交受 Schema 约束的参数。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public final class CallRegisteredHttpApiTool extends RunScopedActionTool {

    static final String TOOL_NAME = "call_registered_http_api";

    private static final int MAX_ENDPOINT_ID_LENGTH = 128;
    private static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    private static final int MAX_ARGUMENT_DEPTH = 8;
    private static final int MAX_ARGUMENT_FIELDS = 64;
    private static final int MAX_PREVIEW_CHARACTERS = 2_000;
    private static final int MAX_RESULT_CHARACTERS = 4_000;
    private static final Set<String> INPUT_FIELDS = Set.of("endpointId", "arguments");

    private final String actorId;
    private final Set<String> authorizedResourceScopes;
    private final ActionExecutionPort actionExecutionPort;
    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;

    CallRegisteredHttpApiTool(String runId,
                              String actorId,
                              Set<String> authorizedResourceScopes,
                              ActionExecutionPort actionExecutionPort,
                              ObjectMapper objectMapper,
                              EnvelopeCrypto envelopeCrypto) {
        super(runId, ToolBase.builder()
                        .name(TOOL_NAME)
                        .description("调用服务端预注册的 HTTP 业务动作。只能提交 endpointId 和 arguments；"
                                + "外部地址、方法、请求头、凭证、身份和授权范围均由服务端固定。")
                        .inputSchema(inputSchema())
                        .readOnly(false)
                        .concurrencySafe(false),
                new AgentActionPolicy(Set.of(TOOL_NAME)));
        this.actorId = requireText(actorId, "actorId");
        this.authorizedResourceScopes = Set.copyOf(
                authorizedResourceScopes == null ? Set.of() : authorizedResourceScopes);
        this.actionExecutionPort = Objects.requireNonNull(actionExecutionPort, "actionExecutionPort 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto, "envelopeCrypto 不能为空");
    }

    @Override
    protected void validateSchema(Map<String, Object> input) {
        if (!input.keySet().equals(INPUT_FIELDS)) {
            throw new IllegalArgumentException("HTTP 动作只能包含 endpointId 和 arguments");
        }
        String endpointId = stringValue(input.get("endpointId"), "endpointId");
        if (endpointId.length() > MAX_ENDPOINT_ID_LENGTH) {
            throw new IllegalArgumentException("endpointId 不能超过 " + MAX_ENDPOINT_ID_LENGTH + " 个字符");
        }
        arguments(input.get("arguments"));
    }

    @Override
    protected AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId) {
        String endpointId = stringValue(input.get("endpointId"), "endpointId");
        Map<String, Object> arguments = arguments(input.get("arguments"));
        ActionDefinitionView definition = requireDefinition(
                actionExecutionPort.resolveDefinition(endpointId), endpointId);
        if (!definition.modelWritableFields().containsAll(arguments.keySet())) {
            throw new IllegalArgumentException("参数包含模型不可写字段");
        }

        CanonicalArguments canonical = canonicalize(arguments);
        validateEndpointSchema(definition.argumentSchema(), canonical.json());
        String operationFingerprint = envelopeCrypto.hmac(String.join("\n",
                "action-operation-fingerprint-v1", runId(), TOOL_NAME,
                definition.definitionVersion(), definition.endpointRevision(), canonical.json()));
        String operationId = "op_" + envelopeCrypto.hmac(
                "action-operation-id-v1\n" + operationFingerprint);
        String actionSummary = definition.displayName() + "：" + definition.safeSummary();
        String parameterPreview = parameterPreview(canonical.json(), definition.argumentSchema());

        return new AgentActionCandidate(
                runId(), toolCallId, TOOL_NAME, definition.toolVersion(), definition.definitionVersion(),
                definition.endpointRevision(), definition.endpointConfigHash(), definition.policyVersion(),
                definition.riskLevel(), definition.resourceScope(), authorizedResourceScopes,
                input, definition.externalizesModelFreeText(), canonical.json(), canonical.hmac(), operationId,
                actionSummary, definition.expectedImpact(), parameterPreview);
    }

    @Override
    protected ToolResultBlock executeVerified(AgentActionCandidate candidate) {
        String executionKey = envelopeCrypto.hmac(String.join("\n",
                "action-execution-key-v1", candidate.runId(), candidate.toolCallId(),
                candidate.toolDefinitionVersion()));
        String operationFingerprint = envelopeCrypto.hmac(String.join("\n",
                "action-operation-fingerprint-v1", candidate.runId(), candidate.toolName(),
                candidate.toolDefinitionVersion(), candidate.endpointRevision(), candidate.canonicalArguments()));
        ActionExecutionView result = actionExecutionPort.execute(new ActionExecutionCommand(
                candidate.runId(), candidate.toolCallId(), candidate.toolName(), candidate.toolVersion(),
                endpointId(candidate.modelArguments()), candidate.toolDefinitionVersion(),
                candidate.endpointRevision(), candidate.endpointConfigHash(), candidate.policyVersion(),
                executionKey, operationFingerprint, candidate.operationId(), candidate.canonicalArguments(),
                candidate.canonicalInputHmac(), actorId, candidate.authorizedResourceScopes(),
                candidate.resourceScope()));
        return ToolResultBlock.text(truncate(renderResult(result), MAX_RESULT_CHARACTERS));
    }

    private CanonicalArguments canonicalize(Map<String, Object> arguments) {
        try {
            JsonNode input = objectMapper.valueToTree(arguments);
            FieldCounter counter = new FieldCounter();
            JsonNode sorted = sort(input, 1, counter);
            String json = objectMapper.writeValueAsString(sorted);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
                throw new IllegalArgumentException("动作参数大小超过 64 KB");
            }
            return new CanonicalArguments(json, envelopeCrypto.hmac(json));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("动作参数无法规范化", e);
        }
    }

    private JsonNode sort(JsonNode node, int depth, FieldCounter counter) {
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw new IllegalArgumentException("动作参数嵌套深度超过 " + MAX_ARGUMENT_DEPTH);
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        if (++counter.fields > MAX_ARGUMENT_FIELDS) {
                            throw new IllegalArgumentException("动作参数字段数量超过 " + MAX_ARGUMENT_FIELDS);
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
            throw new IllegalArgumentException("动作参数包含非有限数字");
        }
        return node.deepCopy();
    }

    private void validateEndpointSchema(Map<String, Object> schema, String canonicalJson) {
        try {
            String schemaJson = objectMapper.writeValueAsString(validationSchema(schema));
            Schema validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaJson);
            List<Error> errors = validator.validate(canonicalJson, InputFormat.JSON);
            if (!errors.isEmpty()) {
                String details = errors.stream()
                        .sorted(Comparator.comparing(message -> message.getInstanceLocation().toString()))
                        .limit(3)
                        .map(message -> message.getInstanceLocation() + " " + message.getKeyword())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown");
                throw new IllegalArgumentException("参数不符合 Endpoint Schema: " + details);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Endpoint Schema 无法校验", e);
        }
    }

    private String parameterPreview(String canonicalJson, Map<String, Object> schema) {
        try {
            JsonNode preview = objectMapper.readTree(canonicalJson);
            redact(preview, objectMapper.valueToTree(schema));
            return truncate(objectMapper.writeValueAsString(preview), MAX_PREVIEW_CHARACTERS);
        } catch (Exception e) {
            throw new IllegalArgumentException("动作参数预览生成失败", e);
        }
    }

    private static void redact(JsonNode value, JsonNode schema) {
        if (schema != null && schema.get("sensitive") != null && schema.get("sensitive").asBoolean()) {
            return;
        }
        if (value == null || schema == null) {
            return;
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            List<String> fieldNames = value.properties().stream().map(Map.Entry::getKey).toList();
            for (String fieldName : fieldNames) {
                JsonNode fieldSchema = properties == null ? null : properties.get(fieldName);
                if (fieldSchema != null && fieldSchema.get("sensitive") != null
                        && fieldSchema.get("sensitive").asBoolean()) {
                    value.asObject().put(fieldName, "[REDACTED]");
                } else {
                    redact(value.get(fieldName), fieldSchema);
                }
            }
        } else if (value.isArray()) {
            JsonNode itemSchema = schema.get("items");
            value.forEach(item -> redact(item, itemSchema));
        }
    }

    private static Object validationSchema(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                if (!"sensitive".equals(name) && !"x-location".equals(name)) {
                    copy.put(name, validationSchema(child));
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CallRegisteredHttpApiTool::validationSchema).toList();
        }
        return value;
    }

    private static ActionDefinitionView requireDefinition(ActionDefinitionView definition, String endpointId) {
        if (definition == null || !endpointId.equals(definition.actionId())
                || isBlank(definition.displayName()) || isBlank(definition.toolVersion())
                || isBlank(definition.definitionVersion()) || isBlank(definition.endpointRevision())
                || isBlank(definition.endpointConfigHash()) || isBlank(definition.policyVersion())
                || definition.argumentSchema() == null || definition.riskLevel() == null
                || isBlank(definition.resourceScope()) || isBlank(definition.safeSummary())
                || isBlank(definition.expectedImpact())) {
            throw new IllegalArgumentException("HTTP Endpoint 定义不完整");
        }
        return definition;
    }

    private static String renderResult(ActionExecutionView result) {
        if (result == null || result.status() == null || isBlank(result.executionId())) {
            throw new IllegalStateException("动作执行结果不完整");
        }
        String identity = "executionId=" + result.executionId()
                + (isBlank(result.operationId()) ? "" : "，operationId=" + result.operationId());
        return switch (result.status()) {
            case SUCCEEDED -> "外部动作执行成功，" + identity + resultText(result.resultSummary());
            case FAILED -> "外部动作执行失败，" + identity + codeText(result) + resultText(result.resultSummary());
            case OUTCOME_UNKNOWN -> "外部操作可能已执行，" + identity
                    + "。请人工核对，禁止自动重试。" + codeText(result);
            case CANCELLED -> "外部动作已取消，" + identity + codeText(result);
            case PENDING, EXECUTING -> "外部动作仍在处理，" + identity + "。请勿重复提交。";
        };
    }

    private static String codeText(ActionExecutionView result) {
        return isBlank(result.resultCode()) ? "" : "，结果码=" + result.resultCode();
    }

    private static String resultText(String summary) {
        return isBlank(summary) ? "" : "，结果=" + summary;
    }

    private static Map<String, Object> arguments(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("arguments 必须是 JSON object");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("arguments 字段名不能为空");
            }
            arguments.put(name, item);
        });
        return Map.copyOf(arguments);
    }

    private static String endpointId(Map<String, Object> modelArguments) {
        return stringValue(modelArguments.get("endpointId"), "endpointId");
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

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("endpointId", Map.of(
                "type", "string",
                "maxLength", MAX_ENDPOINT_ID_LENGTH,
                "description", "服务端已注册的 Endpoint 标识"));
        properties.put("arguments", Map.of(
                "type", "object",
                "description", "符合该 Endpoint 参数 Schema 的业务参数"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.of("endpointId", "arguments"));
        return Map.copyOf(schema);
    }

    private record CanonicalArguments(String json, String hmac) {
    }

    private static final class FieldCounter {
        private int fields;
    }
}

/**
 * 在 HTTP 动作开关开启时为每个可信 Run 创建独立工具实例。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Component
@ConditionalOnProperty(prefix = "fileagent.action.http", name = "enabled", havingValue = "true")
final class CallRegisteredHttpApiToolFactory implements AgentActionToolFactory {

    private static final String LOCAL_OPERATOR = "local-operator";

    private final AgentProperties properties;
    private final ActionExecutionPort actionExecutionPort;
    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;

    CallRegisteredHttpApiToolFactory(AgentProperties properties,
                                     ActionExecutionPort actionExecutionPort,
                                     ObjectMapper objectMapper,
                                     EnvelopeCrypto envelopeCrypto) {
        this.properties = properties;
        this.actionExecutionPort = actionExecutionPort;
        this.objectMapper = objectMapper;
        this.envelopeCrypto = envelopeCrypto;
    }

    @Override
    public RunScopedActionTool create(AgentRunCommand command) {
        if (command == null || command.runId() == null || command.runId().isBlank()) {
            throw new IllegalArgumentException("Agent Run 命令不能为空");
        }
        return new CallRegisteredHttpApiTool(
                command.runId(), LOCAL_OPERATOR, properties.getAuthorizedActionResourceScopes(),
                actionExecutionPort, objectMapper, envelopeCrypto);
    }
}
