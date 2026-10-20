package com.demetrius.fileagent.action.infrastructure.http;

import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 注册式 HTTP 请求的结构化 URI 构建器；参数只能进入 Schema 指定的 path/query 位置。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Component
public class SafeHttpUriBuilder {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,63})}");
    private static final Pattern ABSOLUTE_URI = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    private static final Set<String> DOUBLE_ENCODING_MARKERS = Set.of(
            "%2e", "%2f", "%5c", "%0d", "%0a");

    /**
     * 将注册模板与已校验参数组合为 URL，并把 body 参数分离返回。
     */
    public BuiltHttpRequest build(String baseUrl, String pathTemplate,
                                  Map<String, Object> arguments, Map<String, Object> requestSchema) {
        validateDefinition(baseUrl, pathTemplate, requestSchema);
        HttpUrl parsedBase = parseBaseUrl(baseUrl);
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        Map<String, Map<String, Object>> fieldSchemas = fieldSchemas(requestSchema);
        if (!fieldSchemas.keySet().containsAll(supplied.keySet())) {
            throw new IllegalArgumentException("动作参数包含 Schema 未声明字段");
        }

        HttpUrl.Builder url = parsedBase.newBuilder();
        Set<String> usedPathFields = appendPath(url, pathTemplate, supplied, fieldSchemas);
        Map<String, Object> body = new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String field = entry.getKey();
                    String location = location(fieldSchemas.get(field));
                    switch (location) {
                        case "path" -> {
                            if (!usedPathFields.contains(field)) {
                                throw new IllegalArgumentException("路径参数未出现在模板中: " + field);
                            }
                        }
                        case "query" -> appendQuery(url, field, entry.getValue());
                        case "body" -> body.put(field, entry.getValue());
                        default -> throw new IllegalArgumentException("不支持的参数位置: " + location);
                    }
                });

        HttpUrl built = url.build();
        if (!parsedBase.scheme().equals(built.scheme()) || !parsedBase.host().equals(built.host())
                || parsedBase.port() != built.port()) {
            throw new IllegalArgumentException("URI 参数不得改变注册目标");
        }
        return new BuiltHttpRequest(built, body);
    }

    /** 在启动注册阶段校验目标、模板占位符和 Schema 位置声明完全一致。 */
    public void validateDefinition(String baseUrl, String pathTemplate, Map<String, Object> requestSchema) {
        parseBaseUrl(baseUrl);
        validateTemplate(pathTemplate);
        Map<String, Map<String, Object>> schemas = fieldSchemas(requestSchema);
        Set<String> placeholders = new java.util.LinkedHashSet<>();
        for (String segment : pathTemplate.substring(1).split("/", -1)) {
            Matcher matcher = PLACEHOLDER.matcher(segment);
            if (matcher.matches()) {
                placeholders.add(matcher.group(1));
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : schemas.entrySet()) {
            String location = location(entry.getValue());
            if (!("path".equals(location) || "query".equals(location) || "body".equals(location))) {
                throw new IllegalArgumentException("不支持的参数位置: " + location);
            }
            if ("path".equals(location) && !placeholders.contains(entry.getKey())) {
                throw new IllegalArgumentException("路径参数未出现在模板中: " + entry.getKey());
            }
        }
        for (String placeholder : placeholders) {
            if (!"path".equals(location(schemas.get(placeholder)))) {
                throw new IllegalArgumentException("路径占位符未声明为 path 参数: " + placeholder);
            }
        }
    }

    private static HttpUrl parseBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(requireText(baseUrl, "baseUrl"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("baseUrl 必须是无 userinfo/query/fragment 的 HTTP(S) 地址");
            }
            validateRegisteredPath(uri.getRawPath());
            return HttpUrl.get(uri.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不安全的注册 baseUrl", e);
        }
    }

    private static void validateRegisteredPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            return;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (!segment.isEmpty()) {
                validateUriValue(segment);
            }
        }
    }

    private static void validateTemplate(String pathTemplate) {
        String template = requireText(pathTemplate, "pathTemplate");
        if (!template.startsWith("/") || template.startsWith("//")
                || template.indexOf('\\') >= 0 || containsControl(template)) {
            throw new IllegalArgumentException("不安全的路径模板");
        }
        for (String segment : template.substring(1).split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(segment);
            if (!matcher.matches()) {
                if (segment.indexOf('{') >= 0 || segment.indexOf('}') >= 0) {
                    throw new IllegalArgumentException("路径占位符必须独占一个 segment");
                }
                validateUriValue(segment);
            }
        }
    }

    private static Set<String> appendPath(HttpUrl.Builder url, String template,
                                          Map<String, Object> arguments,
                                          Map<String, Map<String, Object>> fieldSchemas) {
        Set<String> used = new java.util.LinkedHashSet<>();
        for (String segment : template.substring(1).split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(segment);
            if (!matcher.matches()) {
                url.addPathSegment(segment);
                continue;
            }
            String field = matcher.group(1);
            if (!"path".equals(location(fieldSchemas.get(field)))) {
                throw new IllegalArgumentException("路径占位符未声明为 path 参数: " + field);
            }
            Object value = arguments.get(field);
            if (value == null) {
                throw new IllegalArgumentException("缺少路径参数: " + field);
            }
            String text = scalarText(value, field);
            validateUriValue(text);
            url.addPathSegment(text);
            used.add(field);
        }
        return Collections.unmodifiableSet(used);
    }

    private static void appendQuery(HttpUrl.Builder url, String field, Object value) {
        Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
        for (Object item : values) {
            String text = scalarText(item, field);
            validateUriValue(text);
            url.addQueryParameter(field, text);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> fieldSchemas(Map<String, Object> requestSchema) {
        if (requestSchema == null || !(requestSchema.get("properties") instanceof Map<?, ?> properties)) {
            throw new IllegalArgumentException("requestSchema 缺少 properties");
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            if (!(value instanceof Map<?, ?> schema)) {
                throw new IllegalArgumentException("字段 Schema 必须是 object: " + key);
            }
            result.put(String.valueOf(key), (Map<String, Object>) schema);
        });
        return result;
    }

    private static String location(Map<String, Object> fieldSchema) {
        if (fieldSchema == null) {
            return "body";
        }
        Object configured = fieldSchema.get("x-location");
        return configured == null ? "body" : String.valueOf(configured).toLowerCase(Locale.ROOT);
    }

    private static String scalarText(Object value, String field) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) {
            throw new IllegalArgumentException("URI 参数必须是标量: " + field);
        }
        return String.valueOf(value);
    }

    private static void validateUriValue(String value) {
        String raw = requireText(value, "URI 参数");
        rejectDangerous(raw);
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不安全 URI 参数: 百分号编码无效", e);
        }
        rejectDangerous(decoded);
        String lowered = decoded.toLowerCase(Locale.ROOT);
        if (DOUBLE_ENCODING_MARKERS.stream().anyMatch(lowered::contains)) {
            throw new IllegalArgumentException("不安全 URI 参数: 疑似双重编码");
        }
    }

    private static void rejectDangerous(String value) {
        String trimmed = value.strip();
        if (trimmed.startsWith("//") || ABSOLUTE_URI.matcher(trimmed).matches()
                || trimmed.indexOf('\\') >= 0 || containsControl(trimmed) || containsDotSegment(trimmed)) {
            throw new IllegalArgumentException("不安全 URI 参数");
        }
    }

    private static boolean containsDotSegment(String value) {
        for (String segment : value.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /** 已构建的目标 URL 与不进入 URI 的请求体参数。 */
    public record BuiltHttpRequest(HttpUrl url, Map<String, Object> bodyArguments) {

        public BuiltHttpRequest {
            bodyArguments = Collections.unmodifiableMap(new LinkedHashMap<>(bodyArguments));
        }
    }
}
