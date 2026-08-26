package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 全局知识检索端口实现：基于 SimpleVectorStore 的相似度检索。
 * 不按 knowledge/tag/sessionId 过滤，检索范围为全部已入库知识片段。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Component
public class KnowledgeSearchPortImpl implements KnowledgeSearchPort {

    private final SimpleVectorStore vectorStore;

    @Value("${fileagent.retrieval-top-k:5}")
    private int topK;

    @Value("${fileagent.retrieval-similarity-threshold:0.7}")
    private double similarityThreshold;

    public KnowledgeSearchPortImpl(SimpleVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeHit> search(String query) {
        if (!StringUtils.hasText(query)) {
            throw new BizException("检索关键词不能为空");
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(document -> new KnowledgeHit(
                        document.getText(),
                        document.getMetadata().get("filename") == null
                                ? null
                                : String.valueOf(document.getMetadata().get("filename"))))
                .toList();
    }
}
