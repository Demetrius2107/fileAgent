package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        for (int fromIndex = 0; fromIndex < retrievableIndexes.size(); fromIndex += batchSize) {
            int toIndex = Math.min(fromIndex + batchSize, retrievableIndexes.size());
            List<Integer> batchIndexes = retrievableIndexes.subList(fromIndex, toIndex);
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
        return embeddings;
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
