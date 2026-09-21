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
}
