package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.MgetRequest;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Elasticsearch 知识索引仓储实现。
 *
 * @author raosaijie
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ElasticsearchKnowledgeIndexRepository implements KnowledgeIndexRepository {

    private static final String PARENT_CHUNK_TYPE = "PARENT";
    /** 单文件分块查看的读取上限：单文件 chunk 数远小于此，防御性兜底 */
    private static final int MAX_FILE_CHUNKS = 10000;

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchKnowledgeProperties properties;

    @Override
    public void saveAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<float[]> embeddings = embedInBatches(chunks);
        for (float[] embedding : embeddings) {
            if (embedding != null && embedding.length != properties.getDimensions()) {
                throw new BizException("Embedding 向量维度与 Elasticsearch 索引配置不一致");
            }
        }

        try {
            BulkResponse response = elasticsearchClient.bulk(buildBulkRequest(chunks, embeddings));
            if (response.errors()) {
                throw new BizException("Elasticsearch 批量索引失败: " + failedIds(response));
            }
            log.info("Elasticsearch 知识片段写入完成: fileId={}, chunks={}",
                    chunks.getFirst().fileId(), chunks.size());
        } catch (IOException e) {
            throw new BizException("Elasticsearch 批量索引失败: " + e.getMessage());
        }
    }

    private List<float[]> embedInBatches(List<KnowledgeChunk> chunks) {
        int batchSize = properties.getEmbeddingBatchSize();
        if (batchSize <= 0) {
            throw new BizException("Embedding 批量大小必须大于 0");
        }
        List<Integer> retrievableIndexes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!PARENT_CHUNK_TYPE.equals(chunks.get(i).metadata().get("chunkType"))) {
                retrievableIndexes.add(i);
            }
        }

        List<float[]> embeddings = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        if (retrievableIndexes.isEmpty()) {
            return embeddings;
        }
        /* Embedding 是外部 HTTP 调用、IO 密集：虚拟线程并发跑批次，信号量限流保护远端 */
        int concurrency = Math.max(1, properties.getEmbeddingConcurrency());
        Semaphore permits = new Semaphore(concurrency);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int fromIndex = 0; fromIndex < retrievableIndexes.size(); fromIndex += batchSize) {
                int toIndex = Math.min(fromIndex + batchSize, retrievableIndexes.size());
                List<Integer> batchIndexes = List.copyOf(retrievableIndexes.subList(fromIndex, toIndex));
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        embedBatch(chunks, batchIndexes, embeddings);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("Embedding 并发执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("Embedding 批量调用失败: " + cause.getMessage());
        } finally {
            /* 成功时无残留任务；失败时中断在途调用，快速失败避免浪费剩余配额 */
            executor.shutdownNow();
        }
        return embeddings;
    }

    private void embedBatch(List<KnowledgeChunk> chunks, List<Integer> batchIndexes, List<float[]> embeddings) {
        List<String> contents = batchIndexes.stream()
                .map(index -> chunks.get(index).content())
                .toList();
        List<float[]> batchEmbeddings = embeddingModel.embed(contents);
        if (batchEmbeddings.size() != batchIndexes.size()) {
            throw new BizException("Embedding 返回数量与知识片段数量不一致");
        }
        for (int i = 0; i < batchIndexes.size(); i++) {
            embeddings.set(batchIndexes.get(i), batchEmbeddings.get(i));
        }
    }

    BulkRequest buildBulkRequest(List<KnowledgeChunk> chunks, List<float[]> embeddings) {
        BulkRequest.Builder request = new BulkRequest.Builder()
                .index(properties.getIndexAlias())
                .requireAlias(true)
                .refresh(Refresh.WaitFor);
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            Map<String, Object> source = toSource(chunk, embeddings.get(i));
            request.operations(operation -> operation.index(index -> index
                    .id(chunk.chunkId())
                    .document(source)));
        }
        return request.build();
    }

    @Override
    public void deleteByFileId(Long fileId) {
        if (fileId == null) {
            return;
        }
        try {
            DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                    .index(properties.getIndexAlias())
                    .query(query -> query.term(term -> term
                            .field("fileId")
                            .value(String.valueOf(fileId))))
                    .build();
            elasticsearchClient.deleteByQuery(request);
        } catch (IOException e) {
            throw new BizException("Elasticsearch 清理知识索引失败: " + e.getMessage());
        }
    }

    @Override
    public List<KnowledgeChunk> findByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        try {
            MgetRequest request = new MgetRequest.Builder()
                    .index(properties.getIndexAlias())
                    .ids(chunkIds)
                    .sourceExcludes("embedding")
                    .build();
            @SuppressWarnings("unchecked")
            MgetResponse<Map> response = elasticsearchClient.mget(request, Map.class);
            Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
            for (MultiGetResponseItem<Map> item : response.docs()) {
                if (item.isResult() && item.result().found() && item.result().source() != null) {
                    byId.put(item.result().id(), toChunk(item.result().id(), item.result().source()));
                }
            }
            return chunkIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new BizException("Elasticsearch 读取知识片段失败: " + e.getMessage());
        }
    }

    @Override
    public List<KnowledgeChunk> findByFileId(Long fileId) {
        if (fileId == null) {
            return List.of();
        }
        try {
            SearchRequest request = new SearchRequest.Builder()
                    .index(properties.getIndexAlias())
                    .query(query -> query.term(term -> term
                            .field("fileId")
                            .value(String.valueOf(fileId))))
                    .size(MAX_FILE_CHUNKS)
                    .source(source -> source.filter(filter -> filter.excludes("embedding")))
                    .build();
            @SuppressWarnings("unchecked")
            SearchResponse<Map> response = elasticsearchClient.search(request, Map.class);
            /* 不依赖 ES text 字段排序（需 fielddata）：Java 端按 chunkIndex 回排，同序号下 CHILD 先于 PARENT */
            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> toChunk(hit.id(), hit.source()))
                    .sorted(Comparator.comparingInt(KnowledgeChunk::chunkIndex)
                            .thenComparing(chunk -> PARENT_CHUNK_TYPE.equals(chunk.metadata().get("chunkType")) ? 1 : 0))
                    .toList();
        } catch (IOException e) {
            throw new BizException("Elasticsearch 读取知识片段失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private KnowledgeChunk toChunk(String id, Map<String, Object> source) {
        Object metadata = source.get("metadata");
        Map<String, Object> metadataMap = metadata instanceof Map ? (Map<String, Object>) metadata : Map.of();
        return new KnowledgeChunk(
                stringValue(source, "chunkId", id),
                longValue(source.get("fileId")),
                stringValue(source, "ragName", null),
                stringValue(source, "knowledgeTag", null),
                stringValue(source, "filename", null),
                stringValue(source, "content", null),
                intValue(source.get("chunkIndex")),
                metadataMap);
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

    private Map<String, Object> toSource(KnowledgeChunk chunk, float[] embedding) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("chunkId", chunk.chunkId());
        source.put("fileId", String.valueOf(chunk.fileId()));
        source.put("ragName", chunk.ragName());
        source.put("knowledgeTag", chunk.knowledgeTag());
        source.put("filename", chunk.filename());
        source.put("content", chunk.content());
        source.put("chunkIndex", chunk.chunkIndex());
        source.put("sourceType", value(chunk.metadata(), "sourceType"));
        source.put("sheetName", value(chunk.metadata(), "sheetName"));
        source.put("sectionId", value(chunk.metadata(), "sectionId"));
        source.put("parentId", value(chunk.metadata(), "parentId"));
        source.put("chunkType", value(chunk.metadata(), "chunkType"));
        source.put("rowIndex", integerValue(chunk.metadata(), "rowIndex"));
        source.put("metadata", chunk.metadata());
        if (embedding != null) {
            source.put("embedding", embedding);
        }
        return source;
    }

    private String value(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private String failedIds(BulkResponse response) {
        List<String> failed = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                failed.add(item.id() + "(" + item.error().reason() + ")");
            }
        }
        return String.join(",", failed);
    }
}
