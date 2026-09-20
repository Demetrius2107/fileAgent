package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch BM25、KNN 与 RRF 混合知识检索实现。
 *
 * @author raosaijie
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchPortImpl implements KnowledgeSearchPort {

    private static final List<String> BM25_FIELDS = List.of(
            "content^3", "filename^2", "sheetName^1.5", "ragName", "knowledgeTag");

    private static final int MAX_TOP_K = 100;
    private static final int MAX_KNN_CANDIDATES = 1000;
    private static final int MAX_FINAL_TOP_K = 50;
    private static final double MAX_WEIGHT = 2.0;
    private static final String FALLBACK_RERANK_DISABLED_BY_POLICY = "RERANK_DISABLED_BY_POLICY";
    private static final String FALLBACK_RERANK_FAILED = "RERANK_FAILED";

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchKnowledgeProperties properties;
    private final RrfFusion rrfFusion;
    private final DashScopeKnowledgeReranker knowledgeReranker;

    @Override
    public List<KnowledgeHit> search(String query) {
        return search(SearchQuery.of(query));
    }

    @Override
    public List<KnowledgeHit> search(SearchQuery query) {
        return searchInternal(query).finalHits();
    }

    @Override
    public SearchResult searchDetailed(SearchQuery query) {
        return searchInternal(query);
    }

    private SearchResult searchInternal(SearchQuery query) {
        validate(query);
        SearchOptions options = query.options();
        if (options != null) {
            validateOptions(options);
        }
        long startedAt = System.nanoTime();
        try {
            List<KnowledgeHit> bm25Hits = search(buildBm25Request(query, options));
            float[] queryEmbedding = embeddingModel.embed(query.text());
            if (queryEmbedding.length != properties.getDimensions()) {
                throw new BizException("查询向量维度与 Elasticsearch 索引配置不一致");
            }
            List<KnowledgeHit> knnHits = search(buildKnnRequest(query, toFloatList(queryEmbedding), options));
            List<KnowledgeHit> fused = fuse(bm25Hits, knnHits, options);
            List<KnowledgeHit> ordered = fused;
            boolean rerankApplied = false;
            String fallbackCode = null;
            if (options == null || options.rerankEnabled()) {
                try {
                    ordered = knowledgeReranker.rerank(query.text(), fused);
                    rerankApplied = true;
                } catch (RuntimeException e) {
                    log.warn("语义重排失败，降级为 RRF 排序: query={}, error={}",
                            query.text(), e.getMessage());
                    fallbackCode = FALLBACK_RERANK_FAILED;
                }
            } else {
                fallbackCode = FALLBACK_RERANK_DISABLED_BY_POLICY;
            }
            int finalTopK = effectiveFinalTopK(options);
            List<KnowledgeHit> truncated = ordered.stream().limit(finalTopK).toList();
            List<KnowledgeHit> result = options != null && !options.parentExpansionEnabled()
                    ? truncated
                    : expandParents(truncated, finalTopK);
            logHits(query, bm25Hits.size(), knnHits.size(), result, startedAt);
            return new SearchResult(fused, result, options, rerankApplied, fallbackCode);
        } catch (IOException e) {
            throw new BizException("Elasticsearch 知识检索失败: " + e.getMessage());
        }
    }

    SearchRequest buildBm25Request(SearchQuery query) {
        return buildBm25Request(query, query.options());
    }

    SearchRequest buildBm25Request(SearchQuery query, SearchOptions options) {
        Query textQuery = Query.of(builder -> builder.multiMatch(multiMatch -> multiMatch
                .query(query.text())
                .fields(BM25_FIELDS)));
        return new SearchRequest.Builder()
                .index(properties.getIndexAlias())
                .size(effectiveBm25TopK(options))
                .source(source -> source.filter(filter -> filter.excludes("embedding")))
                .query(withFilters(textQuery, buildFilters(query)))
                .build();
    }

    SearchRequest buildKnnRequest(SearchQuery query, List<Float> queryVector) {
        return buildKnnRequest(query, queryVector, query.options());
    }

    SearchRequest buildKnnRequest(SearchQuery query, List<Float> queryVector, SearchOptions options) {
        KnnSearch.Builder knn = new KnnSearch.Builder()
                .field("embedding")
                .queryVector(queryVector)
                .k(effectiveKnnTopK(options))
                .numCandidates(effectiveKnnCandidates(options));
        List<Query> filters = buildFilters(query);
        if (!filters.isEmpty()) {
            knn.filter(filters);
        }
        return new SearchRequest.Builder()
                .index(properties.getIndexAlias())
                .size(effectiveKnnTopK(options))
                .source(source -> source.filter(filter -> filter.excludes("embedding")))
                .knn(knn.build())
                .build();
    }

    private void validate(SearchQuery query) {
        if (query == null || !StringUtils.hasText(query.text())) {
            throw new BizException("检索关键词不能为空");
        }
    }

    private void validateOptions(SearchOptions options) {
        if (options.strategyId() != null && options.strategyId().length() > 64) {
            throw new BizException("检索策略 ID 超出服务端允许长度");
        }
        if (options.bm25TopK() < 1 || options.bm25TopK() > MAX_TOP_K
                || options.knnTopK() < 1 || options.knnTopK() > MAX_TOP_K) {
            throw new BizException("检索 TopK 超出服务端允许范围");
        }
        if (options.knnCandidates() < options.knnTopK() || options.knnCandidates() > MAX_KNN_CANDIDATES) {
            throw new BizException("KNN 候选数超出服务端允许范围");
        }
        if (options.bm25Weight() <= 0 || options.bm25Weight() > MAX_WEIGHT
                || options.knnWeight() <= 0 || options.knnWeight() > MAX_WEIGHT) {
            throw new BizException("BM25/KNN 权重超出服务端允许范围");
        }
        if (options.finalTopK() < 1 || options.finalTopK() > MAX_FINAL_TOP_K) {
            throw new BizException("合并后最终上限超出服务端允许范围");
        }
    }

    private List<KnowledgeHit> fuse(List<KnowledgeHit> bm25Hits, List<KnowledgeHit> knnHits,
                                    SearchOptions options) {
        return options == null
                ? rrfFusion.fuse(bm25Hits, knnHits, properties.getRrfRankConstant())
                : rrfFusion.fuse(bm25Hits, knnHits, properties.getRrfRankConstant(),
                        options.bm25Weight(), options.knnWeight());
    }

    private int effectiveBm25TopK(SearchOptions options) {
        return options == null ? properties.getBm25TopK() : options.bm25TopK();
    }

    private int effectiveKnnTopK(SearchOptions options) {
        return options == null ? properties.getKnnTopK() : options.knnTopK();
    }

    private int effectiveKnnCandidates(SearchOptions options) {
        return options == null ? properties.getKnnCandidates() : options.knnCandidates();
    }

    private int effectiveFinalTopK(SearchOptions options) {
        return options == null ? properties.getFinalTopK() : options.finalTopK();
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeHit> search(SearchRequest request) throws IOException {
        SearchResponse<Map> response = elasticsearchClient.search(request, Map.class);
        return mapHits(response);
    }

    private Query withFilters(Query query, List<Query> filters) {
        if (filters.isEmpty()) {
            return query;
        }
        return Query.of(builder -> builder.bool(bool -> bool.must(query).filter(filters)));
    }

    private List<Query> buildFilters(SearchQuery query) {
        List<Query> filters = new ArrayList<>();
        filters.add(retrievableChunkFilter());
        addTermFilter(filters, "ragName.keyword", query.ragName());
        addTermFilter(filters, "knowledgeTag.keyword", query.knowledgeTag());
        if (query.fileId() != null) {
            addTermFilter(filters, "fileId", String.valueOf(query.fileId()));
        }
        return List.copyOf(filters);
    }

    private Query retrievableChunkFilter() {
        Query child = Query.of(builder -> builder.term(term -> term
                .field("chunkType").value("CHILD")));
        Query missingType = Query.of(builder -> builder.bool(bool -> bool
                .mustNot(Query.of(query -> query.exists(exists -> exists.field("chunkType"))))));
        return Query.of(builder -> builder.bool(bool -> bool
                .should(child)
                .should(missingType)
                .minimumShouldMatch("1")));
    }

    private void addTermFilter(List<Query> filters, String field, String value) {
        if (StringUtils.hasText(value)) {
            filters.add(Query.of(builder -> builder.term(term -> term.field(field).value(value))));
        }
    }

    private List<KnowledgeHit> expandParents(List<KnowledgeHit> hits, int limit) throws IOException {
        LinkedHashSet<String> parentIds = new LinkedHashSet<>();
        for (KnowledgeHit hit : hits) {
            if (StringUtils.hasText(hit.parentId())) {
                parentIds.add(hit.parentId());
            }
        }
        if (parentIds.isEmpty()) {
            return hits;
        }
        MgetRequest request = new MgetRequest.Builder()
                .index(properties.getIndexAlias())
                .ids(List.copyOf(parentIds))
                .sourceExcludes("embedding")
                .build();
        @SuppressWarnings("unchecked")
        MgetResponse<Map> response = elasticsearchClient.mget(request, Map.class);
        Map<String, KnowledgeHit> parents = new LinkedHashMap<>();
        for (MultiGetResponseItem<Map> item : response.docs()) {
            if (item.isResult() && item.result().found() && item.result().source() != null) {
                parents.put(item.result().id(),
                        toKnowledgeHit(item.result().id(), item.result().source(), 0.0));
            }
        }
        Map<String, KnowledgeHit> result = new LinkedHashMap<>();
        for (KnowledgeHit hit : hits) {
            KnowledgeHit parent = parents.get(hit.parentId());
            KnowledgeHit context = parent == null ? hit : withScore(parent, hit.score());
            result.putIfAbsent(context.chunkId(), context);
        }
        return result.values().stream().limit(limit).toList();
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private KnowledgeHit toKnowledgeHit(Hit<Map> hit) {
        return toKnowledgeHit(hit.id(), hit.source(), hit.score() == null ? 0.0 : hit.score());
    }

    private List<KnowledgeHit> mapHits(SearchResponse<Map> response) {
        return response.hits().hits().stream()
                .map(this::toKnowledgeHit)
                .filter(hit -> hit.chunkId() != null)
                .toList();
    }

    private KnowledgeHit toKnowledgeHit(String id, Map<String, Object> source, double score) {
        if (source == null) {
            return new KnowledgeHit(id, null, null, null, null, null, null, 0, score);
        }
        return new KnowledgeHit(
                stringValue(source, "chunkId", id),
                longValue(source.get("fileId")),
                stringValue(source, "content", null),
                stringValue(source, "filename", null),
                stringValue(source, "sheetName", null),
                stringValue(source, "sectionId", null),
                stringValue(source, "parentId", null),
                intValue(source.get("chunkIndex")),
                score);
    }

    private KnowledgeHit withScore(KnowledgeHit hit, double score) {
        return new KnowledgeHit(hit.chunkId(), hit.fileId(), hit.content(), hit.filename(),
                hit.sheetName(), hit.sectionId(), hit.parentId(), hit.chunkIndex(), score);
    }

    private String stringValue(Map<String, Object> source, String key, String defaultValue) {
        Object value = source.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    private void logHits(SearchQuery query, int bm25Count, int knnCount,
                         List<KnowledgeHit> hits, long startedAt) {
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        log.debug("知识混合检索完成: query={}, ragName={}, knowledgeTag={}, fileId={}, "
                        + "bm25Hits={}, knnHits={}, finalHits={}, elapsedMs={}",
                query.text(), query.ragName(), query.knowledgeTag(), query.fileId(),
                bm25Count, knnCount, hits.size(), elapsedMillis);
        for (KnowledgeHit hit : hits) {
            log.debug("知识命中: chunkId={}, score={}, file={}, section={}, chunkIndex={}",
                    hit.chunkId(), hit.score(), hit.filename(), hit.sectionId(), hit.chunkIndex());
        }
    }
}
