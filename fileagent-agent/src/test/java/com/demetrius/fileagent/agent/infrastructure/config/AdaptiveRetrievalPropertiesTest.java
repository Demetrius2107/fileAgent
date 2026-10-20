package com.demetrius.fileagent.agent.infrastructure.config;

import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptiveRetrievalPropertiesTest {

    @Test
    void defaultsShouldPassStartupValidation() {
        assertThatCode(() -> new AdaptiveRetrievalProperties().validate(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonPositiveTopKShouldFailStartup() {
        AdaptiveRetrievalProperties properties = new AdaptiveRetrievalProperties();
        properties.getSingleHop().setBm25TopK(0);

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void outOfRangeWeightShouldFailStartup() {
        AdaptiveRetrievalProperties properties = new AdaptiveRetrievalProperties();
        properties.getAggregation().setBm25Weight(2.5);

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void candidatesBelowTopKShouldFailStartup() {
        AdaptiveRetrievalProperties properties = new AdaptiveRetrievalProperties();
        properties.getMultiHop().setKnnCandidates(10);
        properties.getMultiHop().setKnnTopK(20);

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void runTimeoutNotExceedingThreeToolCallsShouldFailStartup() {
        AdaptiveRetrievalProperties properties = new AdaptiveRetrievalProperties();
        properties.setRunTimeout(Duration.ofSeconds(14));

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void nonPositiveRunTimeoutShouldFailStartup() {
        AdaptiveRetrievalProperties properties = new AdaptiveRetrievalProperties();
        properties.setRunTimeout(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> properties.validate(Duration.ofSeconds(5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void adaptiveRetrievalShouldDefaultToDisabled() {
        assertThat(new AgentProperties().isAdaptiveRetrievalEnabled()).isFalse();
    }

    @Test
    void actionResourceScopesShouldBindEmptyAndCommaSeparatedValues() {
        AgentProperties empty = bindAgentProperties("");
        AgentProperties configured = bindAgentProperties("expense:draft,ticket:update");

        assertThat(empty.getAuthorizedActionResourceScopes()).isEmpty();
        assertThat(configured.getAuthorizedActionResourceScopes())
                .containsExactlyInAnyOrder("expense:draft", "ticket:update");
    }

    @Test
    void modelCallDefaultsShouldKeepEightStepsAndAllowSixAdaptiveCalls() {
        AgentProperties base = new AgentProperties();
        AdaptiveRetrievalProperties adaptive = new AdaptiveRetrievalProperties();

        assertThat(base.getMaxSteps()).isEqualTo(8);
        assertThat(base.getMaxModelCalls()).isEqualTo(4);
        assertThat(adaptive.getMaxModelCalls()).isEqualTo(6);
    }

    @Test
    void contextBudgetDefaultsShouldBeValidated() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getMaxPromptCharacters()).isEqualTo(8_000);
        assertThat(properties.getMaxHistoryCharacters()).isEqualTo(8_000);
        assertThat(properties.getMaxSummaryCharacters()).isEqualTo(2_000);
        assertThat(properties.getMaxRecentHistoryCharacters()).isEqualTo(6_000);
        assertThat(properties.getMaxTotalTokens()).isEqualTo(60_000);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void summaryAndRecentHistoryBudgetCannotExceedHistoryBudget() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxSummaryCharacters(5_000);
        properties.setMaxRecentHistoryCharacters(5_000);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(BizException.class);
    }

    @Test
    void runtimeCapacityDefaultsShouldBeValidated() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getMaxConcurrentRuns()).isEqualTo(4);
        assertThat(properties.getMaxQueuedRuns()).isEqualTo(32);
        assertThat(properties.getReplayMaxEvents()).isEqualTo(100);
        assertThat(properties.getReplayMaxBytes()).isEqualTo(256 * 1024);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void invalidRuntimeCapacityShouldFailStartupValidation() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxConcurrentRuns(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(BizException.class);
    }

    private static AgentProperties bindAgentProperties(String scopes) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "fileagent.agent.authorized-action-resource-scopes", scopes));
        return new Binder(source).bind("fileagent.agent", Bindable.of(AgentProperties.class)).get();
    }
}
