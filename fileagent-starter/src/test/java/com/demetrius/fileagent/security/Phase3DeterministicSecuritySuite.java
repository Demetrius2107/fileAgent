package com.demetrius.fileagent.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 配置默认值门禁：危险动作必须显式启用，不能因部署遗漏配置而开放。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3DeterministicSecuritySuite {

    @Test
    void shouldKeepPythonActionsDisabledByDefault() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).as("starter application.yml").isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(yaml).contains(
                    "actions-enabled: ${FILEAGENT_AGENT_ACTIONS_ENABLED:false}",
                    "enabled: ${FILEAGENT_SANDBOX_ENABLED:false}");
        }
    }
}
