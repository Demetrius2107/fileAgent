package com.demetrius.fileagent.agent.domain.service;

import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.SearchOptions;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptiveRetrievalPolicyTest {

    private AdaptiveRetrievalPolicy policy() {
        return new AdaptiveRetrievalPolicy(new AdaptiveRetrievalProperties().toTiers());
    }

    @Test
    void optionsShouldMapSpecTiersPerQueryType() {
        AdaptiveRetrievalPolicy policy = policy();

        SearchOptions singleHop = policy.optionsFor(RetrievalQueryType.SINGLE_HOP);
        assertThat(singleHop.strategyId()).isEqualTo("SINGLE_HOP");
        assertThat(singleHop.bm25TopK()).isEqualTo(20);
        assertThat(singleHop.knnTopK()).isEqualTo(20);
        assertThat(singleHop.knnCandidates()).isEqualTo(100);
        assertThat(singleHop.bm25Weight()).isEqualTo(1.0);
        assertThat(singleHop.knnWeight()).isEqualTo(1.0);
        assertThat(singleHop.finalTopK()).isEqualTo(5);
        assertThat(singleHop.rerankEnabled()).isTrue();
        assertThat(singleHop.parentExpansionEnabled()).isTrue();

        SearchOptions multiHop = policy.optionsFor(RetrievalQueryType.MULTI_HOP);
        assertThat(multiHop.strategyId()).isEqualTo("MULTI_HOP");
        assertThat(multiHop.finalTopK()).isEqualTo(8);
        assertThat(multiHop.rerankEnabled()).isTrue();
        assertThat(multiHop.parentExpansionEnabled()).isFalse();

        SearchOptions comparison = policy.optionsFor(RetrievalQueryType.COMPARISON);
        assertThat(comparison.strategyId()).isEqualTo("COMPARISON");
        assertThat(comparison.finalTopK()).isEqualTo(8);
        assertThat(comparison.parentExpansionEnabled()).isFalse();

        SearchOptions aggregation = policy.optionsFor(RetrievalQueryType.AGGREGATION);
        assertThat(aggregation.bm25TopK()).isEqualTo(50);
        assertThat(aggregation.bm25Weight()).isEqualTo(1.3);
        assertThat(aggregation.knnWeight()).isEqualTo(0.7);
        assertThat(aggregation.finalTopK()).isEqualTo(12);
        assertThat(aggregation.rerankEnabled()).isFalse();
        assertThat(aggregation.parentExpansionEnabled()).isFalse();

        SearchOptions timeSensitive = policy.optionsFor(RetrievalQueryType.TIME_SENSITIVE);
        assertThat(timeSensitive.bm25TopK()).isEqualTo(30);
        assertThat(timeSensitive.bm25Weight()).isEqualTo(1.3);
        assertThat(timeSensitive.knnWeight()).isEqualTo(0.7);
        assertThat(timeSensitive.finalTopK()).isEqualTo(8);
        assertThat(timeSensitive.rerankEnabled()).isTrue();
        assertThat(timeSensitive.parentExpansionEnabled()).isFalse();
    }

    @Test
    void optionsShouldRejectNoneQueryType() {
        assertThatThrownBy(() -> policy().optionsFor(RetrievalQueryType.NONE))
                .isInstanceOf(BizException.class);
    }

    @Test
    void optionsShouldRejectUnknownQueryType() {
        assertThatThrownBy(() -> policy().optionsFor(null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void subQueryBoundsShouldMatchSpecPerType() {
        AdaptiveRetrievalPolicy policy = policy();

        assertThat(policy.minSubQueries(RetrievalQueryType.SINGLE_HOP)).isEqualTo(1);
        assertThat(policy.maxSubQueries(RetrievalQueryType.SINGLE_HOP)).isEqualTo(1);
        assertThat(policy.minSubQueries(RetrievalQueryType.MULTI_HOP)).isEqualTo(2);
        assertThat(policy.maxSubQueries(RetrievalQueryType.MULTI_HOP)).isEqualTo(3);
        assertThat(policy.minSubQueries(RetrievalQueryType.COMPARISON)).isEqualTo(2);
        assertThat(policy.maxSubQueries(RetrievalQueryType.COMPARISON)).isEqualTo(3);
        assertThat(policy.minSubQueries(RetrievalQueryType.AGGREGATION)).isEqualTo(1);
        assertThat(policy.maxSubQueries(RetrievalQueryType.AGGREGATION)).isEqualTo(3);
        assertThat(policy.minSubQueries(RetrievalQueryType.TIME_SENSITIVE)).isEqualTo(1);
        assertThat(policy.maxSubQueries(RetrievalQueryType.TIME_SENSITIVE)).isEqualTo(2);
    }

    @Test
    void finalHitCapShouldMatchSpecPerType() {
        AdaptiveRetrievalPolicy policy = policy();

        assertThat(policy.finalHitCap(RetrievalQueryType.SINGLE_HOP)).isEqualTo(5);
        assertThat(policy.finalHitCap(RetrievalQueryType.MULTI_HOP)).isEqualTo(8);
        assertThat(policy.finalHitCap(RetrievalQueryType.COMPARISON)).isEqualTo(8);
        assertThat(policy.finalHitCap(RetrievalQueryType.AGGREGATION)).isEqualTo(12);
        assertThat(policy.finalHitCap(RetrievalQueryType.TIME_SENSITIVE)).isEqualTo(8);
    }

    @Test
    void subQueryBoundsShouldRejectNoneQueryType() {
        assertThatThrownBy(() -> policy().maxSubQueries(RetrievalQueryType.NONE))
                .isInstanceOf(BizException.class);
    }
}
