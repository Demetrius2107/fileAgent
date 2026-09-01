package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeSearchPortImpl} 测试。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchPortImplTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private DashScopeKnowledgeReranker knowledgeReranker;

    private ElasticsearchKnowledgeProperties properties;
    private KnowledgeSearchPortImpl searchPort;

    @BeforeEach
    void setUp() {
        properties = new ElasticsearchKnowledgeProperties();
        properties.setDimensions(2);
        properties.setBm25TopK(10);
        properties.setKnnTopK(10);
        properties.setKnnCandidates(20);
        properties.setFinalTopK(2);
        lenient().when(knowledgeReranker.rerank(any(String.class), any(List.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        searchPort = new KnowledgeSearchPortImpl(
                elasticsearchClient, embeddingModel, properties, new RrfFusion(), knowledgeReranker);
    }

    @Test
    void searchShouldRejectBlankQuery() throws IOException {
        assertThatThrownBy(() -> searchPort.search("  "))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> searchPort.search((String) null))
                .isInstanceOf(BizException.class);

        verify(elasticsearchClient, never()).search(any(SearchRequest.class), eq(Map.class));
    }

    @Test
    void bm25AndKnnShouldUseTheSamePlatformFilters() {
        KnowledgeSearchPort.SearchQuery query = new KnowledgeSearchPort.SearchQuery(
                "年度目标", "管理制度", "绩效", 7L);

        SearchRequest bm25 = searchPort.buildBm25Request(query);
        SearchRequest knn = searchPort.buildKnnRequest(query, List.of(0.1F, 0.2F));

        assertThat(bm25.toString())
                .contains("content^3", "filename^2", "ragName.keyword", "管理制度",
                        "knowledgeTag.keyword", "绩效", "fileId", "7", "chunkType");
        assertThat(knn.toString())
                .contains("embedding", "ragName.keyword", "管理制度",
                        "knowledgeTag.keyword", "绩效", "fileId", "7", "chunkType");
    }

    @Test
    void searchShouldApplyRerankerBeforeFinalTopK() throws IOException {
        when(embeddingModel.embed("查询")).thenReturn(new float[]{0.1F, 0.2F});
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(response(hit("A", 1.0), hit("B", 0.9)),
                        response(hit("B", 0.95), hit("C", 0.8)));
        when(knowledgeReranker.rerank(eq("查询"), any(List.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<KnowledgeSearchPort.KnowledgeHit> hits = invocation.getArgument(1);
                    return List.of(hits.get(2), hits.get(1), hits.get(0));
                });

        List<KnowledgeSearchPort.KnowledgeHit> result = searchPort.search("查询");

        assertThat(result).extracting(KnowledgeSearchPort.KnowledgeHit::chunkId)
                .containsExactly("C", "A");
        assertThat(result).allMatch(hit -> hit.score() > 0);
    }

    @Test
    void searchShouldReplaceChildHitWithItsExplicitParent() throws IOException {
        properties.setFinalTopK(1);
        when(embeddingModel.embed("目标")).thenReturn(new float[]{0.1F, 0.2F});
        Hit<Map> child = childHit("7:2", "7:parent:0", 1.0);
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(response(child), response(child));
        when(elasticsearchClient.mget(any(MgetRequest.class), eq(Map.class)))
                .thenReturn(mgetResponse(parentHit("7:parent:0")));

        List<KnowledgeSearchPort.KnowledgeHit> result = searchPort.search("目标");

        assertThat(result).extracting(KnowledgeSearchPort.KnowledgeHit::chunkId)
                .containsExactly("7:parent:0");
        assertThat(result.getFirst().content()).isEqualTo("完整父块");
    }

    @Test
    void searchShouldNotExpandASectionWithoutExplicitParentId() throws IOException {
        properties.setFinalTopK(1);
        when(embeddingModel.embed("查询")).thenReturn(new float[]{0.1F, 0.2F});
        Hit<Map> anchor = hit("7:0", 1.0);
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class)))
                .thenReturn(response(anchor), response(anchor));

        List<KnowledgeSearchPort.KnowledgeHit> result = searchPort.search("查询");

        assertThat(result).extracting(KnowledgeSearchPort.KnowledgeHit::chunkId)
                .containsExactly("7:0");
        verify(elasticsearchClient, never()).mget(any(MgetRequest.class), eq(Map.class));
    }

    @Test
    void searchShouldPropagateRuntimeClientFailure() throws IOException {
        IllegalStateException failure = new IllegalStateException("ES unavailable");
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> searchPort.search("查询")).isSameAs(failure);
    }

    @SafeVarargs
    private SearchResponse<Map> response(Hit<Map>... hits) {
        return response(hits.length, hits);
    }

    @SafeVarargs
    private SearchResponse<Map> response(long total, Hit<Map>... hits) {
        return SearchResponse.of(response -> response
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(metadata -> metadata
                        .total(value -> value.value(total).relation(TotalHitsRelation.Eq))
                        .hits(List.of(hits))));
    }

    @SafeVarargs
    private MgetResponse<Map> mgetResponse(Hit<Map>... hits) {
        List<MultiGetResponseItem<Map>> items = java.util.Arrays.stream(hits)
                .map(hit -> MultiGetResponseItem.<Map>of(item -> item.result(result -> result
                        .index(properties.getIndexAlias())
                        .id(hit.id())
                        .found(true)
                        .source(hit.source()))))
                .toList();
        return MgetResponse.of(response -> response.docs(items));
    }

    private Hit<Map> childHit(String id, String parentId, double score) {
        Map<String, Object> source = new java.util.LinkedHashMap<>(hit(id, score).source());
        source.put("parentId", parentId);
        return Hit.of(hit -> hit.index(properties.getIndexAlias()).id(id).score(score).source(source));
    }

    private Hit<Map> parentHit(String id) {
        return Hit.of(hit -> hit
                .index(properties.getIndexAlias())
                .id(id)
                .score(0.0)
                .source(Map.of(
                        "chunkId", id,
                        "fileId", "7",
                        "content", "完整父块",
                        "filename", "年度目标.xlsx",
                        "sheetName", "目标",
                        "sectionId", "sheet-0-section-0",
                        "chunkIndex", 0)));
    }

    private Hit<Map> hit(String id, double score) {
        int chunkIndex = id.contains(":")
                ? Integer.parseInt(id.substring(id.indexOf(':') + 1))
                : id.charAt(0) - 'A';
        return Hit.of(hit -> hit
                .index(properties.getIndexAlias())
                .id(id)
                .score(score)
                .source(Map.of(
                        "chunkId", id,
                        "fileId", "7",
                        "content", "片段 " + id,
                        "filename", "年度目标.xlsx",
                        "sheetName", "目标",
                        "sectionId", "sheet-0-section-0",
                        "chunkIndex", chunkIndex)));
    }
}
