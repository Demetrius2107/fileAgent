package com.demetrius.fileagent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态前端契约测试：三个资源存在，HTML 包含前端脚本依赖的稳定 ID。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
class StaticPageContractTest {

    private static final List<String> STABLE_IDS = List.of(
            "session-panel", "new-session-button", "session-list",
            "chat-panel", "chat-title", "message-list", "chat-form", "prompt-input",
            "send-button", "stop-button",
            "knowledge-panel", "upload-button", "knowledge-list",
            "upload-dialog", "upload-form", "upload-name", "upload-tag", "upload-files",
            "toggle-model-settings", "model-settings-dialog", "model-settings-form",
            "model-config-list", "provider-select", "base-url-input", "api-key-input",
            "model-name-input", "model-config-error", "model-config-cancel", "model-config-save"
    );

    @Test
    void staticResourcesShouldExist() throws IOException {
        assertThat(readResource("static/index.html")).isNotBlank();
        assertThat(readResource("static/app.css")).isNotBlank();
        assertThat(readResource("static/app.js")).isNotBlank();
    }

    @Test
    void indexHtmlShouldContainStableElementIds() throws IOException {
        String html = readResource("static/index.html");

        for (String id : STABLE_IDS) {
            assertThat(html)
                    .as("index.html 应包含 id=\"%s\"", id)
                    .contains("id=\"" + id + "\"");
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("资源应存在: %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
