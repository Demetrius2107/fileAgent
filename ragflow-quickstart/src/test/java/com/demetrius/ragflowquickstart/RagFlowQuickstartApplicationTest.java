package com.demetrius.ragflowquickstart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quickstart 应用装配测试，无需连接真实 RAGFlow 服务。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
@SpringBootTest
class RagFlowQuickstartApplicationTest {

    @Autowired
    private RagFlowClient ragFlowClient;

    @Test
    void shouldLoadApplicationContext() {
        assertThat(ragFlowClient).isNotNull();
    }
}
