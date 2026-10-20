package com.demetrius.fileagent.action.infrastructure.http;

import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/**
 * 动作参数的确定性 JSON 快照；对象键递归排序、数组保序，并为快照生成服务端 HMAC。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public record CanonicalActionArguments(String json, String hmac) {

    static final int MAX_ARGUMENT_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FIELDS = 64;

    /** 将模型参数转为有边界、可重复计算 HMAC 的规范 JSON。 */
    public static CanonicalActionArguments from(Object arguments,
                                                ObjectMapper objectMapper,
                                                EnvelopeCrypto envelopeCrypto) {
        if (arguments == null) {
            throw new IllegalArgumentException("动作参数不能为空");
        }
        try {
            JsonNode input = objectMapper.valueToTree(arguments);
            Counter counter = new Counter();
            JsonNode sorted = sort(input, objectMapper, 1, counter);
            String json = objectMapper.writeValueAsString(sorted);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
                throw new IllegalArgumentException("动作参数大小超过 64 KB");
            }
            return new CanonicalActionArguments(json, envelopeCrypto.hmac(json));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("动作参数无法规范化", e);
        }
    }

    private static JsonNode sort(JsonNode node, ObjectMapper objectMapper, int depth, Counter counter) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("动作参数嵌套深度超过 " + MAX_DEPTH);
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> {
                        counter.fields++;
                        if (counter.fields > MAX_FIELDS) {
                            throw new IllegalArgumentException("动作参数字段数量超过 " + MAX_FIELDS);
                        }
                        sorted.set(entry.getKey(), sort(entry.getValue(), objectMapper, depth + 1, counter));
                    });
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            node.forEach(value -> sorted.add(sort(value, objectMapper, depth + 1, counter)));
            return sorted;
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("动作参数包含非有限数字");
        }
        return node.deepCopy();
    }

    private static final class Counter {
        private int fields;
    }
}
