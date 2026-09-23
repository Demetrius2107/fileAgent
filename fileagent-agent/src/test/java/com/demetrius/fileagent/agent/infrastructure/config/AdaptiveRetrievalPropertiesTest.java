package com.demetrius.fileagent.agent.infrastructure.config;

import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
