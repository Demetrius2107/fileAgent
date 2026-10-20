package com.demetrius.fileagent.action.infrastructure.http;

import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册 URI 的结构化构建和注入攻击拒绝测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class SafeHttpUriBuilderTest {

    private final SafeHttpUriBuilder builder = new SafeHttpUriBuilder();

    @Test
    void shouldPutOnlyDeclaredFieldsIntoPathQueryAndBody() {
        Map<String, Object> schema = schema(Map.of(
                "expenseId", field("string", "path"),
                "tag", field("string", "query"),
                "description", field("string", "body")));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("description", "hotel");
        arguments.put("tag", List.of("travel", "urgent"));
        arguments.put("expenseId", "draft 1");

        SafeHttpUriBuilder.BuiltHttpRequest request = builder.build(
                "https://api.example.com/v1", "/expenses/{expenseId}", arguments, schema);

        HttpUrl url = request.url();
        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("api.example.com");
        assertThat(url.encodedPath()).isEqualTo("/v1/expenses/draft%201");
        assertThat(url.queryParameterValues("tag")).containsExactly("travel", "urgent");
        assertThat(request.bodyArguments()).containsExactlyEntriesOf(Map.of("description", "hotel"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/path",
            "//evil.example/path",
            "https://user@evil.example/path",
            "folder\\admin",
            "value\r\nX-Evil: true",
            "..",
            "%2e%2e",
            "%2e%2e%2fadmin",
            "%252e%252e",
            "%252f%252fevil.example"
    })
    void shouldRejectUriInjectionInPathOrQuery(String attack) {
        Map<String, Object> pathSchema = schema(Map.of("value", field("string", "path")));
        Map<String, Object> querySchema = schema(Map.of("value", field("string", "query")));

        assertThatThrownBy(() -> builder.build(
                "https://api.example.com", "/items/{value}", Map.of("value", attack), pathSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不安全 URI 参数");
        assertThatThrownBy(() -> builder.build(
                "https://api.example.com", "/items", Map.of("value", attack), querySchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不安全 URI 参数");
    }

    @Test
    void shouldRejectUntrustedBaseUrlTemplateAndUndeclaredLocation() {
        Map<String, Object> schema = schema(Map.of("value", field("string", "path")));

        assertThatThrownBy(() -> builder.build(
                "https://user@api.example.com", "/items/{value}", Map.of("value", "1"), schema))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(
                "https://api.example.com", "//evil.example/{value}", Map.of("value", "1"), schema))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(
                "https://api.example.com", "/items/{missing}", Map.of("value", "1"), schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径");
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", properties);
    }

    private static Map<String, Object> field(String type, String location) {
        return Map.of("type", type, "x-location", location, "maxLength", 200);
    }
}
