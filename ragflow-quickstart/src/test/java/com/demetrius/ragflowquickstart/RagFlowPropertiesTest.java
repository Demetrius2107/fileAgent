package com.demetrius.ragflowquickstart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAGFlow 地址规范化规则测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
class RagFlowPropertiesTest {

    @Test
    void shouldAppendApiVersionOnce() {
        assertThat(new RagFlowProperties("https://cloud.ragflow.io/", "key").apiBaseUrl())
                .isEqualTo("https://cloud.ragflow.io/api/v1");
        assertThat(new RagFlowProperties("https://example.test/api/v1", "key").apiBaseUrl())
                .isEqualTo("https://example.test/api/v1");
    }
}
