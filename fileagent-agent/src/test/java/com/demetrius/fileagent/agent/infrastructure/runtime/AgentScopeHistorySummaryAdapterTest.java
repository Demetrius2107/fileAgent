package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.port.HistorySummaryPort;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史摘要 JSON 结构校验测试。
 *
 * @author raosaijie
 */
class AgentScopeHistorySummaryAdapterTest {

    private final AgentScopeHistorySummaryAdapter adapter =
            new AgentScopeHistorySummaryAdapter(null, new ObjectMapper());

    @Test
    void shouldAcceptFixedSummarySchema() {
        HistorySummaryPort.SummaryResult result = adapter.parseSummaryText(
                "{\"confirmedFacts\":[\"用户在上海\"],\"userConstraints\":[],"
                        + "\"decisions\":[],\"openQuestions\":[],\"references\":[]}", 10, 20, 30);

        assertThat(result.summary()).contains("用户在上海");
        assertThat(result.totalTokens()).isEqualTo(30);
    }

    @Test
    void shouldRejectSummaryWithMissingFieldOrInvalidJson() {
        assertThatThrownBy(() -> adapter.parseSummaryText(
                "{\"confirmedFacts\":[]}", 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.parseSummaryText("not-json", 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
