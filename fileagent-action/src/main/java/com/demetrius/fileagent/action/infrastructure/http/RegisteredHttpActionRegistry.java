package com.demetrius.fileagent.action.infrastructure.http;

import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.DataEgressPolicy;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.Endpoint;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 注册式 HTTP 动作目录：启动时冻结并校验定义，运行时只接受 Endpoint ID 和受 Schema 约束的参数。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Component
@ConditionalOnProperty(prefix = "fileagent.action.http", name = "enabled", havingValue = "true")
public class RegisteredHttpActionRegistry {

    public static final String TOOL_VERSION = "http-action-v1";

    private static final int MAX_SCHEMA_BYTES = 32 * 1024;
    private static final int MAX_SCHEMA_DEPTH = 8;
    private static final int MAX_SCHEMA_FIELDS = 64;
    private static final int MAX_STRING_LENGTH = 4_096;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final Map<String, RegisteredEndpoint> endpoints;
    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;

    public RegisteredHttpActionRegistry(RegisteredHttpActionProperties properties,
                                        ObjectMapper objectMapper,
                                        EnvelopeCrypto envelopeCrypto) {
        this.objectMapper = objectMapper;
        this.envelopeCrypto = envelopeCrypto;
        Map<String, RegisteredEndpoint> registered = new LinkedHashMap<>();
        List<Endpoint> configured = properties.getEndpoints() == null ? List.of() : properties.getEndpoints();
        for (Endpoint endpoint : configured) {
            RegisteredEndpoint frozen = validateAndFreeze(endpoint);
            if (registered.putIfAbsent(frozen.endpointId(), frozen) != null) {
                throw new IllegalStateException("HTTP endpointId 重复: " + frozen.endpointId());
            }
        }
        this.endpoints = Map.copyOf(registered);
    }

    /** 返回不包含真实 URL、凭证和内部 Header 的跨域定义快照。 */
    public ActionDefinitionView resolveDefinition(String endpointId) {
        RegisteredEndpoint endpoint = requireEndpoint(endpointId);
        return new ActionDefinitionView(
                endpoint.endpointId(), endpoint.displayName(), TOOL_VERSION,
                endpoint.definitionVersion(), endpoint.endpointRevision(), endpoint.configHash(),
                endpoint.policyVersion(), endpoint.requestSchema(), endpoint.riskLevel(),
                endpoint.resourceScope(), endpoint.modelWritableFields(), endpoint.externalizesModelFreeText(),
                endpoint.safeSummary(), endpoint.expectedImpact());
    }

    /** 校验模型参数字段、JSON Schema 和资源上限后返回稳定 JSON/HMAC。 */
    public CanonicalActionArguments validateArguments(String endpointId, Map<String, Object> arguments) {
        RegisteredEndpoint endpoint = requireEndpoint(endpointId);
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        if (!endpoint.modelWritableFields().containsAll(supplied.keySet())) {
            throw new IllegalArgumentException("参数不符合 Schema: 包含模型不可写字段");
        }
        CanonicalActionArguments canonical = CanonicalActionArguments.from(supplied, objectMapper, envelopeCrypto);
        Set<ValidationMessage> errors = endpoint.schema().validate(canonical.json(), InputFormat.JSON);
        if (!errors.isEmpty()) {
            String details = errors.stream()
                    .sorted(Comparator.comparing(message -> message.getInstanceLocation().toString()))
                    .limit(3)
                    .map(message -> message.getInstanceLocation() + " " + message.getType())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("unknown");
            throw new IllegalArgumentException("参数不符合 Schema: " + details);
        }
        return canonical;
    }

    RegisteredEndpoint requireEndpoint(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException("endpointId 不能为空");
        }
        RegisteredEndpoint endpoint = endpoints.get(endpointId);
        if (endpoint == null) {
            throw new IllegalArgumentException("未注册的 HTTP Endpoint: " + endpointId);
        }
        return endpoint;
    }

    private RegisteredEndpoint validateAndFreeze(Endpoint endpoint) {
        if (endpoint == null) {
            throw new IllegalStateException("HTTP Endpoint 配置不能为空");
        }
        String endpointId = requireText(endpoint.getEndpointId(), "endpointId");
        String displayName = requireText(endpoint.getDisplayName(), endpointId + ".displayName");
        String definitionVersion = requireText(endpoint.getDefinitionVersion(), endpointId + ".definitionVersion");
        String endpointRevision = requireText(endpoint.getEndpointRevision(), endpointId + ".endpointRevision");
        String policyVersion = requireText(endpoint.getPolicyVersion(), endpointId + ".policyVersion");
        String method = requireText(endpoint.getMethod(), endpointId + ".method").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalStateException(endpointId + ".method 不受支持: " + method);
        }
        String baseUrl = validateBaseUrl(endpointId, endpoint.getBaseUrl(), endpoint.getCredentialRef());
        String pathTemplate = requireText(endpoint.getPathTemplate(), endpointId + ".pathTemplate");
        if (!pathTemplate.startsWith("/")) {
            throw new IllegalStateException(endpointId + ".pathTemplate 必须以 / 开头");
        }
        ActionRiskLevel riskLevel = requireValue(endpoint.getRiskLevel(), endpointId + ".riskLevel");
        DataEgressPolicy egress = endpoint.getDataEgressPolicy();
        if (egress == null) {
            throw new IllegalStateException(endpointId + ".dataEgressPolicy 必须显式配置");
        }
        Set<String> modelWritableFields = immutableSortedSet(egress.getModelWritableFields());
        Map<String, Object> requestSchema = validateSchema(endpointId, endpoint.getRequestSchema(), modelWritableFields);
        new SafeHttpUriBuilder().validateDefinition(baseUrl, pathTemplate, requestSchema);
        String resourceScope = requireText(endpoint.getResourceScope(), endpointId + ".resourceScope");
        String safeSummary = requireText(endpoint.getSafeSummary(), endpointId + ".safeSummary");
        String expectedImpact = requireText(endpoint.getExpectedImpact(), endpointId + ".expectedImpact");
        Duration connectTimeout = positive(endpoint.getConnectTimeout(), endpointId + ".connectTimeout");
        Duration readTimeout = positive(endpoint.getReadTimeout(), endpointId + ".readTimeout");
        Duration callTimeout = positive(endpoint.getCallTimeout(), endpointId + ".callTimeout");
        if (connectTimeout.compareTo(callTimeout) > 0 || readTimeout.compareTo(callTimeout) > 0) {
            throw new IllegalStateException(endpointId + " 的连接/读取超时不能大于总超时");
        }
        if (endpoint.getMaxResponseBytes() <= 0 || endpoint.getMaxResponseBytes() > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException(endpointId + ".maxResponseBytes 必须在 1 到 262144 之间");
        }
        Set<String> contentTypes = immutableSortedSet(endpoint.getAllowedContentTypes());
        if (contentTypes.isEmpty() || contentTypes.stream().anyMatch(Predicate.not(RegisteredHttpActionRegistry::validContentType))) {
            throw new IllegalStateException(endpointId + ".allowedContentTypes 配置无效");
        }
        String idempotencyHeader = optionalText(endpoint.getIdempotencyHeader());
        if (riskLevel != ActionRiskLevel.READ_ONLY && idempotencyHeader == null) {
            throw new IllegalStateException(endpointId + " 写动作必须配置远端幂等 Header");
        }
        if (idempotencyHeader != null && !HEADER_NAME.matcher(idempotencyHeader).matches()) {
            throw new IllegalStateException(endpointId + ".idempotencyHeader 不是合法 Header 名称");
        }

        Map<String, Object> configSnapshot = new LinkedHashMap<>();
        configSnapshot.put("endpointId", endpointId);
        configSnapshot.put("displayName", displayName);
        configSnapshot.put("definitionVersion", definitionVersion);
        configSnapshot.put("endpointRevision", endpointRevision);
        configSnapshot.put("policyVersion", policyVersion);
        configSnapshot.put("method", method);
        configSnapshot.put("baseUrl", baseUrl);
        configSnapshot.put("pathTemplate", pathTemplate);
        configSnapshot.put("requestSchema", requestSchema);
        configSnapshot.put("riskLevel", riskLevel.name());
        configSnapshot.put("modelWritableFields", new ArrayList<>(modelWritableFields));
        configSnapshot.put("externalizesModelFreeText", egress.isExternalizesModelFreeText());
        configSnapshot.put("resourceScope", resourceScope);
        configSnapshot.put("safeSummary", safeSummary);
        configSnapshot.put("expectedImpact", expectedImpact);
        configSnapshot.put("credentialConfigured", optionalText(endpoint.getCredentialRef()) != null);
        configSnapshot.put("connectTimeoutMillis", connectTimeout.toMillis());
        configSnapshot.put("readTimeoutMillis", readTimeout.toMillis());
        configSnapshot.put("callTimeoutMillis", callTimeout.toMillis());
        configSnapshot.put("maxResponseBytes", endpoint.getMaxResponseBytes());
        configSnapshot.put("allowedContentTypes", new ArrayList<>(contentTypes));
        configSnapshot.put("idempotencyHeader", idempotencyHeader);
        String configHash = CanonicalActionArguments.from(configSnapshot, objectMapper, envelopeCrypto).hmac();

        String schemaJson = writeJson(requestSchema);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson);
        return new RegisteredEndpoint(endpointId, displayName, definitionVersion, endpointRevision, policyVersion,
                method, baseUrl, pathTemplate, requestSchema, schema, riskLevel, modelWritableFields,
                egress.isExternalizesModelFreeText(), resourceScope, safeSummary, expectedImpact,
                optionalText(endpoint.getCredentialRef()), connectTimeout, readTimeout, callTimeout,
                endpoint.getMaxResponseBytes(), contentTypes, idempotencyHeader, configHash);
    }

    private Map<String, Object> validateSchema(String endpointId, Map<String, Object> source,
                                               Set<String> modelWritableFields) {
        if (source == null || source.isEmpty()) {
            throw new IllegalStateException(endpointId + ".requestSchema 不能为空");
        }
        Map<String, Object> schema = immutableSchemaMap(source);
        String schemaJson = writeJson(schema);
        if (schemaJson.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            throw new IllegalStateException(endpointId + ".requestSchema 大小超过 32 KB");
        }
        if (!"object".equals(schema.get("type")) || !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw new IllegalStateException(endpointId + ".requestSchema 必须是 additionalProperties=false 的 object");
        }
        if (containsKey(schema, "$ref")) {
            throw new IllegalStateException(endpointId + ".requestSchema 不允许使用 $ref");
        }
        SchemaStats stats = new SchemaStats();
        inspectSchema(schema, 1, stats);
        if (stats.maxDepth > MAX_SCHEMA_DEPTH) {
            throw new IllegalStateException(endpointId + ".requestSchema 嵌套深度超过 " + MAX_SCHEMA_DEPTH);
        }
        if (stats.fields > MAX_SCHEMA_FIELDS) {
            throw new IllegalStateException(endpointId + ".requestSchema 字段数量超过 " + MAX_SCHEMA_FIELDS);
        }
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> value ? value : Map.of();
        if (!properties.keySet().containsAll(modelWritableFields)) {
            throw new IllegalStateException(endpointId + ".dataEgressPolicy 包含 Schema 未声明字段");
        }
        return schema;
    }

    private static void inspectSchema(Object value, int depth, SchemaStats stats) {
        stats.maxDepth = Math.max(stats.maxDepth, depth);
        if (value instanceof Map<?, ?> map) {
            if ("string".equals(map.get("type"))) {
                Object maxLength = map.get("maxLength");
                if (!(maxLength instanceof Number number)
                        || number.intValue() <= 0 || number.intValue() > MAX_STRING_LENGTH) {
                    throw new IllegalStateException("字符串 Schema 必须配置 1..4096 的 maxLength");
                }
            }
            if (map.get("properties") instanceof Map<?, ?> properties) {
                stats.fields += properties.size();
            }
            map.values().forEach(child -> inspectSchema(child, depth + 1, stats));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(child -> inspectSchema(child, depth + 1, stats));
        }
    }

    private String validateBaseUrl(String endpointId, String rawBaseUrl, String credentialRef) {
        String baseUrl = requireText(rawBaseUrl, endpointId + ".baseUrl");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(endpointId + ".baseUrl 格式无效", e);
        }
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(endpointId + ".baseUrl 必须是无 userinfo/query/fragment 的 HTTP(S) 地址");
        }
        if (optionalText(credentialRef) != null && !"https".equals(scheme)) {
            throw new IllegalStateException(endpointId + " 带凭证的 Endpoint 必须使用 HTTPS");
        }
        return uri.normalize().toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 动作配置无法序列化", e);
        }
    }

    private static boolean containsKey(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.containsKey(key) || map.values().stream().anyMatch(child -> containsKey(child, key));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(child -> containsKey(child, key));
        }
        return false;
    }

    private static Map<String, Object> immutableSchemaMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if ("required".equals(key) && value instanceof Collection<?> required) {
                copy.put(key, required.stream().map(String::valueOf).sorted().toList());
            } else {
                copy.put(key, immutableSchemaValue(value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableSchemaValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                if ("required".equals(name) && child instanceof Collection<?> required) {
                    copy.put(name, required.stream().map(String::valueOf).sorted().toList());
                } else {
                    copy.put(name, immutableSchemaValue(child));
                }
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(RegisteredHttpActionRegistry::immutableSchemaValue).toList();
        }
        return value;
    }

    private static Set<String> immutableSortedSet(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(requireText(value, "集合元素"));
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static boolean validContentType(String value) {
        return value != null && !value.isBlank() && value.contains("/")
                && value.chars().noneMatch(Character::isWhitespace);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 不能为空");
        }
        return value.strip();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <T> T requireValue(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " 不能为空");
        }
        return value;
    }

    private static final class SchemaStats {
        private int maxDepth;
        private int fields;
    }

    /** 已冻结的 Endpoint 定义，仅在 action 基础设施内部使用。 */
    record RegisteredEndpoint(
            String endpointId,
            String displayName,
            String definitionVersion,
            String endpointRevision,
            String policyVersion,
            String method,
            String baseUrl,
            String pathTemplate,
            Map<String, Object> requestSchema,
            JsonSchema schema,
            ActionRiskLevel riskLevel,
            Set<String> modelWritableFields,
            boolean externalizesModelFreeText,
            String resourceScope,
            String safeSummary,
            String expectedImpact,
            String credentialRef,
            Duration connectTimeout,
            Duration readTimeout,
            Duration callTimeout,
            int maxResponseBytes,
            Set<String> allowedContentTypes,
            String idempotencyHeader,
            String configHash) {
    }
}
