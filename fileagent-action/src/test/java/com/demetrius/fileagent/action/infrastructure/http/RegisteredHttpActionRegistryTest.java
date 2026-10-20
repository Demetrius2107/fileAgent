package com.demetrius.fileagent.action.infrastructure.http;

import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.DataEgressPolicy;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.Endpoint;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 注册式 HTTP 动作的启动校验、参数校验和规范化测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class RegisteredHttpActionRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EnvelopeCrypto envelopeCrypto = deterministicCrypto();

    @Test
    void shouldRejectDuplicateEndpointId() {
        RegisteredHttpActionProperties properties = properties(validEndpoint("expense"), validEndpoint("expense"));

        assertThatThrownBy(() -> registry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpointId 重复");
    }

    @Test
    void shouldRejectCredentialOverPlainHttp() {
        Endpoint endpoint = validEndpoint("expense");
        endpoint.setBaseUrl("http://api.example.com");
        endpoint.setCredentialRef("FILEAGENT_EXPENSE_TOKEN");

        assertThatThrownBy(() -> registry(properties(endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("凭证")
                .hasMessageContaining("HTTPS");
    }

    @Test
    void shouldRejectAnonymousPlainHttpInProductionRegistry() {
        Endpoint endpoint = validEndpoint("expense");
        endpoint.setBaseUrl("http://api.example.com");
        endpoint.setCredentialRef(null);

        assertThatThrownBy(() -> registry(properties(endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产配置必须使用 HTTPS");
    }

    @Test
    void shouldRejectWriteActionWithoutRemoteIdempotencyHeader() {
        Endpoint endpoint = validEndpoint("expense");
        endpoint.setIdempotencyHeader(null);

        assertThatThrownBy(() -> registry(properties(endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("幂等 Header");
    }

    @Test
    void shouldRejectMissingDataEgressPolicy() {
        Endpoint endpoint = validEndpoint("expense");
        endpoint.setDataEgressPolicy(null);

        assertThatThrownBy(() -> registry(properties(endpoint)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataEgressPolicy");
    }

    @Test
    void shouldRejectUnsafePathTemplateDuringRegistration() {
        Endpoint endpoint = validEndpoint("expense");
        endpoint.setPathTemplate("//evil.example/expenses");

        assertThatThrownBy(() -> registry(properties(endpoint)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径模板");
    }

    @Test
    void shouldRejectSchemaBeyondDepthFieldAndStringLimits() {
        Endpoint deep = validEndpoint("deep");
        deep.setRequestSchema(deepSchema(10));
        assertThatThrownBy(() -> registry(properties(deep)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("嵌套深度");

        Endpoint manyFields = validEndpoint("many-fields");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            fields.put("field" + index, Map.of("type", "integer"));
        }
        manyFields.setRequestSchema(objectSchema(fields));
        manyFields.getDataEgressPolicy().setModelWritableFields(fields.keySet());
        assertThatThrownBy(() -> registry(properties(manyFields)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("字段数量");

        Endpoint unboundedText = validEndpoint("unbounded-text");
        unboundedText.setRequestSchema(objectSchema(Map.of("description", Map.of("type", "string"))));
        unboundedText.getDataEgressPolicy().setModelWritableFields(Set.of("description"));
        assertThatThrownBy(() -> registry(properties(unboundedText)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxLength");
    }

    @Test
    void shouldCreateStableConfigHashRegardlessOfMapOrder() {
        Endpoint first = validEndpoint("expense");
        Endpoint second = validEndpoint("expense");
        Map<String, Object> reversedProperties = new LinkedHashMap<>();
        reversedProperties.put("description", Map.of("maxLength", 200, "type", "string"));
        reversedProperties.put("amount", Map.of("minimum", 0, "type", "number"));
        second.setRequestSchema(objectSchema(reversedProperties));

        String firstHash = registry(properties(first)).resolveDefinition("expense").endpointConfigHash();
        String secondHash = registry(properties(second)).resolveDefinition("expense").endpointConfigHash();

        assertThat(firstHash).isEqualTo(secondHash);
    }

    @Test
    void shouldValidateAndCanonicalizeArguments() {
        RegisteredHttpActionRegistry registry = registry(properties(validEndpoint("expense")));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("description", "hotel");
        arguments.put("amount", 100);

        CanonicalActionArguments canonical = registry.validateArguments("expense", arguments);

        assertThat(canonical.json()).isEqualTo("{\"amount\":100,\"description\":\"hotel\"}");
        assertThat(canonical.hmac()).isEqualTo("hmac:" + canonical.json());

        assertThatThrownBy(() -> registry.validateArguments("expense", Map.of("unknown", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数不符合 Schema");
    }

    @Test
    void shouldPreserveArrayOrderAndRejectNonFiniteOrOversizedInput() {
        CanonicalActionArguments first = CanonicalActionArguments.from(
                Map.of("items", List.of("a", "b")), objectMapper, envelopeCrypto);
        CanonicalActionArguments second = CanonicalActionArguments.from(
                Map.of("items", List.of("b", "a")), objectMapper, envelopeCrypto);

        assertThat(first.json()).isNotEqualTo(second.json());
        assertThatThrownBy(() -> CanonicalActionArguments.from(
                Map.of("amount", Double.NaN), objectMapper, envelopeCrypto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非有限数字");
        assertThatThrownBy(() -> CanonicalActionArguments.from(
                Map.of("description", "x".repeat(70_000)), objectMapper, envelopeCrypto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数大小");
    }

    private RegisteredHttpActionRegistry registry(RegisteredHttpActionProperties properties) {
        return new RegisteredHttpActionRegistry(properties, objectMapper, envelopeCrypto);
    }

    private static RegisteredHttpActionProperties properties(Endpoint... endpoints) {
        RegisteredHttpActionProperties properties = new RegisteredHttpActionProperties();
        properties.setEnabled(true);
        properties.setEndpoints(List.of(endpoints));
        return properties;
    }

    private static Endpoint validEndpoint(String endpointId) {
        Endpoint endpoint = new Endpoint();
        endpoint.setEndpointId(endpointId);
        endpoint.setDisplayName("创建报销草稿");
        endpoint.setDefinitionVersion("definition-v1");
        endpoint.setEndpointRevision("endpoint-v1");
        endpoint.setPolicyVersion("policy-v1");
        endpoint.setMethod("POST");
        endpoint.setBaseUrl("https://api.example.com");
        endpoint.setPathTemplate("/expenses/drafts");
        endpoint.setRequestSchema(objectSchema(new LinkedHashMap<>(Map.of(
                "amount", Map.of("type", "number", "minimum", 0),
                "description", Map.of("type", "string", "maxLength", 200)))));
        endpoint.setRiskLevel(ActionRiskLevel.LOW_RISK_WRITE);
        DataEgressPolicy policy = new DataEgressPolicy();
        policy.setModelWritableFields(Set.of("amount", "description"));
        policy.setExternalizesModelFreeText(true);
        endpoint.setDataEgressPolicy(policy);
        endpoint.setResourceScope("expense:draft");
        endpoint.setSafeSummary("创建报销草稿");
        endpoint.setExpectedImpact("将在报销系统创建一条可撤销草稿");
        endpoint.setCredentialRef("FILEAGENT_EXPENSE_TOKEN");
        endpoint.setConnectTimeout(Duration.ofSeconds(2));
        endpoint.setReadTimeout(Duration.ofSeconds(5));
        endpoint.setCallTimeout(Duration.ofSeconds(8));
        endpoint.setMaxResponseBytes(64 * 1024);
        endpoint.setAllowedContentTypes(Set.of("application/json"));
        endpoint.setIdempotencyHeader("Idempotency-Key");
        return endpoint;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> fields) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", fields);
        schema.put("required", new ArrayList<>(fields.keySet()));
        return schema;
    }

    private static Map<String, Object> deepSchema(int depth) {
        Map<String, Object> current = Map.of("type", "string", "maxLength", 10);
        for (int index = 0; index < depth; index++) {
            current = objectSchema(Map.of("level" + index, current));
        }
        return current;
    }

    private static EnvelopeCrypto deterministicCrypto() {
        EnvelopeCrypto crypto = mock(EnvelopeCrypto.class);
        when(crypto.hmac(anyString())).thenAnswer(invocation -> "hmac:" + invocation.getArgument(0));
        return crypto;
    }
}
