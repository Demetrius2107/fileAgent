package com.demetrius.fileagent.api.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatStreamEvent} 契约测试：工厂方法、空列表兜底与不可变防护。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
class ChatStreamEventTest {

    @Test
    void messageFactoryShouldFillTypeAndContentOnly() {
        ChatStreamEvent event = ChatStreamEvent.message("你好");

        assertThat(event.type()).isEqualTo("message");
        assertThat(event.content()).isEqualTo("你好");
        assertThat(event.answerSource()).isNull();
        assertThat(event.files()).isEmpty();
        assertThat(event.messageId()).isNull();
        assertThat(event.code()).isNull();
        assertThat(event.message()).isNull();
    }

    @Test
    void sourcesFactoryShouldCarryAnswerSourceAndFiles() {
        ChatStreamEvent event = ChatStreamEvent.sources("KNOWLEDGE", List.of("员工手册.pdf", "制度.docx"));

        assertThat(event.type()).isEqualTo("sources");
        assertThat(event.answerSource()).isEqualTo("KNOWLEDGE");
        assertThat(event.files()).containsExactly("员工手册.pdf", "制度.docx");
        assertThat(event.content()).isNull();
        assertThat(event.messageId()).isNull();
        assertThat(event.code()).isNull();
        assertThat(event.message()).isNull();
    }

    @Test
    void doneFactoryShouldCarryMessageId() {
        ChatStreamEvent event = ChatStreamEvent.done(42L);

        assertThat(event.type()).isEqualTo("done");
        assertThat(event.messageId()).isEqualTo(42L);
        assertThat(event.content()).isNull();
        assertThat(event.answerSource()).isNull();
        assertThat(event.files()).isEmpty();
        assertThat(event.code()).isNull();
        assertThat(event.message()).isNull();
    }

    @Test
    void errorFactoryShouldCarryCodeAndMessage() {
        ChatStreamEvent event = ChatStreamEvent.error("MODEL_STREAM_FAILED", "模型调用失败");

        assertThat(event.type()).isEqualTo("error");
        assertThat(event.code()).isEqualTo("MODEL_STREAM_FAILED");
        assertThat(event.message()).isEqualTo("模型调用失败");
        assertThat(event.content()).isNull();
        assertThat(event.files()).isEmpty();
        assertThat(event.messageId()).isNull();
    }

    @Test
    void nullFilesShouldFallBackToEmptyList() {
        ChatStreamEvent event = ChatStreamEvent.sources("MODEL_GENERAL", null);

        assertThat(event.files()).isNotNull().isEmpty();
    }

    @Test
    void filesShouldNotBeModifiableByCaller() {
        List<String> origin = new ArrayList<>();
        origin.add("员工手册.pdf");
        ChatStreamEvent event = ChatStreamEvent.sources("KNOWLEDGE", origin);

        origin.add("恶意追加.md");
        assertThat(event.files()).containsExactly("员工手册.pdf");
        assertThatThrownBy(() -> event.files().add("再追加.md"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
