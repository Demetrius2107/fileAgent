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

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchKnowledgeProperties properties;

    @Override
    public void saveAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<float[]> embeddings = embeddingModel.embed(
                chunks.stream().map(KnowledgeChunk::content).toList());
        if (embeddings.size() != chunks.size()) {
            throw new BizException("Embedding 返回数量与知识片段数量不一致");
        }
        for (int i = 0; i < embeddings.size(); i++) {
            if (embeddings.get(i).length != properties.getDimensions()) {
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
        source.put("rowIndex", integerValue(chunk.metadata(), "rowIndex"));
        source.put("metadata", chunk.metadata());
        source.put("embedding", embedding);
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
