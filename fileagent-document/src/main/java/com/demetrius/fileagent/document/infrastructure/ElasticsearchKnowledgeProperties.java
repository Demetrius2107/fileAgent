package com.demetrius.fileagent.document.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 知识索引与混合检索配置。
 *
 * @author raosaijie
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.elasticsearch")
public class ElasticsearchKnowledgeProperties {

    private String indexAlias = "fileagent-knowledge";
    private String physicalIndex = "fileagent-knowledge-v1";
    private int dimensions = 1024;
    private int embeddingBatchSize = 10;
    private int bm25TopK = 50;
    private int knnTopK = 50;
    private int knnCandidates = 100;
    private int rrfRankConstant = 60;
    private int finalTopK = 12;
}
