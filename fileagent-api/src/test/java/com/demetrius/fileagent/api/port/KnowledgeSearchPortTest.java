package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnowledgeSearchPort} 检索契约扩展（Phase 2A）测试：SearchQuery 携带策略参数、
 * searchDetailed 包装 SearchResult、RetrievalQueryType 六类取值。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-20
 */
class KnowledgeSearchPortTest {

    @Test
    void searchQueryShouldKeepFourArgConstructorAndOfFactory() {
        KnowledgeSearchPort.SearchQuery query = KnowledgeSearchPort.SearchQuery.of("员工手册");

        assertThat(query.text()).isEqualTo("员工手册");
        assertThat(query.ragName()).isNull();
        assertThat(query.knowledgeTag()).isNull();
        assertThat(query.fileId()).isNull();
        assertThat(query.options()).isNull();
    }

    @Test
    void searchQueryShouldCarryStrategyOptions() {
        KnowledgeSearchPort.SearchOptions options = new KnowledgeSearchPort.SearchOptions(
                "SINGLE_HOP", 20, 20, 100, 1.0, 1.0, 5, true, true);

        KnowledgeSearchPort.SearchQuery query =
                new KnowledgeSearchPort.SearchQuery("年假政策", null, null, null, options);

        assertThat(query.options()).isSameAs(options);
        assertThat(query.options().strategyId()).isEqualTo("SINGLE_HOP");
        assertThat(query.options().bm25TopK()).isEqualTo(20);
        assertThat(query.options().knnTopK()).isEqualTo(20);
        assertThat(query.options().knnCandidates()).isEqualTo(100);
        assertThat(query.options().bm25Weight()).isEqualTo(1.0);
        assertThat(query.options().knnWeight()).isEqualTo(1.0);
        assertThat(query.options().finalTopK()).isEqualTo(5);
        assertThat(query.options().rerankEnabled()).isTrue();
        assertThat(query.options().parentExpansionEnabled()).isTrue();
    }

    @Test
    void searchDetailedShouldWrapSearchResultForLegacyQuery() {
        KnowledgeSearchPort.KnowledgeHit hit = new KnowledgeSearchPort.KnowledgeHit(
                "chunk-1", 1L, "员工入职第一年有 5 天年假", "employee-handbook.md",
                null, "section-1", "parent-1", 2, 0.91);
        KnowledgeSearchPort port = query -> List.of(hit);

        KnowledgeSearchPort.SearchResult result =
                port.searchDetailed(KnowledgeSearchPort.SearchQuery.of("年假政策"));

        assertThat(result.candidates()).containsExactly(hit);
        assertThat(result.finalHits()).containsExactly(hit);
        assertThat(result.appliedOptions()).isNull();
        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.fallbackCode()).isNull();
    }

    @Test
    void searchResultShouldFallBackToEmptyLists() {
        KnowledgeSearchPort.SearchResult result = new KnowledgeSearchPort.SearchResult(
                null, null, null, false, "RERANK_FAILED");

        assertThat(result.candidates()).isEmpty();
        assertThat(result.finalHits()).isEmpty();
        assertThat(result.appliedOptions()).isNull();
        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.fallbackCode()).isEqualTo("RERANK_FAILED");
    }

    @Test
    void retrievalQueryTypeShouldCoverSixTypes() {
        assertThat(RetrievalQueryType.values()).containsExactly(
                RetrievalQueryType.NONE,
                RetrievalQueryType.SINGLE_HOP,
                RetrievalQueryType.MULTI_HOP,
                RetrievalQueryType.COMPARISON,
                RetrievalQueryType.AGGREGATION,
                RetrievalQueryType.TIME_SENSITIVE);
    }
}
