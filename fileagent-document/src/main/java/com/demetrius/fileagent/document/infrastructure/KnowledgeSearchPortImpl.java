package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchKnowledgeProperties properties;
    private final RrfFusion rrfFusion;

    @Override
    public List<KnowledgeHit> search(String query) {
        return search(SearchQuery.single(query));
    }

    @Override
    public List<KnowledgeHit> search(SearchQuery query) {
        validate(query);
        long startedAt = System.nanoTime();
        try {
            List<KnowledgeHit> bm25Hits = search(buildBm25Request(query));
            float[] queryEmbedding = embeddingModel.embed(query.text());
            if (queryEmbedding.length != properties.getDimensions()) {
                throw new BizException("查询向量维度与 Elasticsearch 索引配置不一致");
            }
            List<KnowledgeHit> knnHits = search(buildKnnRequest(query, toFloatList(queryEmbedding)));
            List<KnowledgeHit> fused = rrfFusion.fuse(
                    bm25Hits, knnHits, properties.getRrfRankConstant());

            // TODO: 接入 Reranker，对混合召回结果进行语义重排
            List<KnowledgeHit> result = query.listAll()
                    ? expandListRegion(fused)
                    : expandAdjacent(fused.stream().limit(properties.getFinalTopK()).toList());
            logHits(query, bm25Hits.size(), knnHits.size(), result, startedAt);
            return result;
        } catch (IOException e) {
            throw new BizException("Elasticsearch 知识检索失败: " + e.getMessage());
        }
    }

    SearchRequest buildBm25Request(SearchQuery query) {
        Query textQuery = Query.of(builder -> builder.multiMatch(multiMatch -> multiMatch
                .query(query.text())
                .fields(BM25_FIELDS)));
        return new SearchRequest.Builder()
                .index(properties.getIndexAlias())
                .size(properties.getBm25TopK())
                .source(source -> source.filter(filter -> filter.excludes("embedding")))
                .query(withFilters(textQuery, buildFilters(query)))
                .build();
    }

    SearchRequest buildKnnRequest(SearchQuery query, List<Float> queryVector) {
        KnnSearch.Builder knn = new KnnSearch.Builder()
                .field("embedding")
                .queryVector(queryVector)
                .k(properties.getKnnTopK())
                .numCandidates(properties.getKnnCandidates());
        List<Query> filters = buildFilters(query);
        if (!filters.isEmpty()) {
            knn.filter(filters);
        }
        return new SearchRequest.Builder()
                .index(properties.getIndexAlias())
                .size(properties.getKnnTopK())
                .source(source -> source.filter(filter -> filter.excludes("embedding")))
                .knn(knn.build())
                .build();
    }

    private void validate(SearchQuery query) {
        if (query == null || !StringUtils.hasText(query.text())) {
            throw new BizException("检索关键词不能为空");
        }
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
        addTermFilter(filters, "ragName.keyword", query.ragName());
        addTermFilter(filters, "knowledgeTag.keyword", query.knowledgeTag());
        if (query.fileId() != null) {
            addTermFilter(filters, "fileId", String.valueOf(query.fileId()));
        }
        return List.copyOf(filters);
    }

    private void addTermFilter(List<Query> filters, String field, String value) {
        if (StringUtils.hasText(value)) {
            filters.add(Query.of(builder -> builder.term(term -> term.field(field).value(value))));
        }
    }

    private List<KnowledgeHit> expandListRegion(List<KnowledgeHit> fused) throws IOException {
        KnowledgeHit anchor = fused.stream()
                .filter(hit -> hit.fileId() != null && StringUtils.hasText(hit.sectionId()))
                .findFirst()
                .orElse(null);
        if (anchor == null) {
            return fused.stream().limit(properties.getFinalTopK()).toList();
        }
        Query regionQuery = Query.of(builder -> builder.bool(bool -> bool
                .filter(termQuery("fileId", String.valueOf(anchor.fileId())))
                .filter(termQuery("sectionId", anchor.sectionId()))));
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndexAlias())
                .size(properties.getMaxExpandedChunks())
                .source(source -> source.filter(filter -> filter.excludes("embedding")))
                .query(regionQuery)
                .sort(sort -> sort.field(field -> field.field("rowIndex").order(SortOrder.Asc)))
                .sort(sort -> sort.field(field -> field.field("chunkIndex").order(SortOrder.Asc)))
                .build();
        @SuppressWarnings("unchecked")
        SearchResponse<Map> response = elasticsearchClient.search(request, Map.class);
        List<KnowledgeHit> region = mapHits(response).stream()
                .map(hit -> withScore(hit, anchor.score()))
                .toList();
        if (response.hits().total() == null
                || response.hits().total().value() <= region.size()) {
            return region;
        }
        List<KnowledgeHit> truncated = new ArrayList<>(region);
        truncated.add(new KnowledgeHit(
                anchor.fileId() + ":truncated",
                anchor.fileId(),
                "【系统提示：该区域内容超过检索上下文上限，以下资料不是完整列表，请在回答中明确说明。】",
                anchor.filename(),
                anchor.sheetName(),
                anchor.sectionId(),
                Integer.MAX_VALUE,
                anchor.score()));
        return List.copyOf(truncated);
    }

    private List<KnowledgeHit> expandAdjacent(List<KnowledgeHit> anchors) throws IOException {
        if (anchors.isEmpty() || properties.getAdjacentWindow() <= 0) {
            return anchors;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Map<String, Set<String>> acceptableSections = new LinkedHashMap<>();
        Map<String, Double> anchorScores = new LinkedHashMap<>();
        for (KnowledgeHit anchor : anchors) {
            for (int offset = -properties.getAdjacentWindow();
                 offset <= properties.getAdjacentWindow(); offset++) {
                int chunkIndex = anchor.chunkIndex() + offset;
                if (anchor.fileId() == null || chunkIndex < 0) {
                    continue;
                }
                String id = anchor.fileId() + ":" + chunkIndex;
                ids.add(id);
                acceptableSections.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                        .add(anchor.sectionId());
                anchorScores.merge(id, anchor.score(), Math::max);
            }
        }
        if (ids.isEmpty()) {
            return anchors;
        }

        MgetRequest request = new MgetRequest.Builder()
                .index(properties.getIndexAlias())
                .ids(List.copyOf(ids))
                .sourceExcludes("embedding")
                .build();
        @SuppressWarnings("unchecked")
        MgetResponse<Map> response = elasticsearchClient.mget(request, Map.class);
        Map<String, KnowledgeHit> fetched = new LinkedHashMap<>();
        for (MultiGetResponseItem<Map> item : response.docs()) {
            if (!item.isResult() || !item.result().found() || item.result().source() == null) {
                continue;
            }
            KnowledgeHit hit = toKnowledgeHit(item.result().id(), item.result().source(),
                    anchorScores.getOrDefault(item.result().id(), 0.0));
            Set<String> sections = acceptableSections.get(item.result().id());
            if (sections != null && sections.contains(hit.sectionId())) {
                fetched.put(hit.chunkId(), hit);
            }
        }
        for (KnowledgeHit anchor : anchors) {
            fetched.putIfAbsent(anchor.chunkId(), anchor);
        }
        return fetched.values().stream()
                .sorted(Comparator.comparing(KnowledgeHit::filename,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(KnowledgeHit::sectionId,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(KnowledgeHit::chunkIndex))
                .limit(properties.getMaxExpandedChunks())
                .toList();
    }

    private Query termQuery(String field, String value) {
        return Query.of(builder -> builder.term(term -> term.field(field).value(value)));
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
            return new KnowledgeHit(id, null, null, null, null, null, 0, score);
        }
        return new KnowledgeHit(
                stringValue(source, "chunkId", id),
                longValue(source.get("fileId")),
                stringValue(source, "content", null),
                stringValue(source, "filename", null),
                stringValue(source, "sheetName", null),
                stringValue(source, "sectionId", null),
                intValue(source.get("chunkIndex")),
                score);
    }

    private KnowledgeHit withScore(KnowledgeHit hit, double score) {
        return new KnowledgeHit(hit.chunkId(), hit.fileId(), hit.content(), hit.filename(),
                hit.sheetName(), hit.sectionId(), hit.chunkIndex(), score);
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
        log.debug("知识混合检索完成: query={}, answerMode={}, ragName={}, knowledgeTag={}, fileId={}, "
                        + "bm25Hits={}, knnHits={}, finalHits={}, elapsedMs={}",
                query.text(), query.answerMode(), query.ragName(), query.knowledgeTag(), query.fileId(),
                bm25Count, knnCount, hits.size(), elapsedMillis);
        for (KnowledgeHit hit : hits) {
            log.debug("知识命中: chunkId={}, score={}, file={}, section={}, chunkIndex={}",
                    hit.chunkId(), hit.score(), hit.filename(), hit.sectionId(), hit.chunkIndex());
        }
    }
}
