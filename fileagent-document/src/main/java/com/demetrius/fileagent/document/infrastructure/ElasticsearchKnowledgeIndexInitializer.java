package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 初始化 Elasticsearch 知识索引及其稳定别名。
 *
 * @author raosaijie
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchKnowledgeIndexInitializer {

    private final ElasticsearchClient elasticsearchClient;
    private final ElasticsearchKnowledgeProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            boolean aliasExists = elasticsearchClient.indices()
                    .existsAlias(request -> request.name(properties.getIndexAlias()))
                    .value();
            if (aliasExists) {
                ensureParentChildMapping(properties.getIndexAlias());
                return;
            }
            boolean physicalIndexExists = elasticsearchClient.indices()
                    .exists(request -> request.index(properties.getPhysicalIndex()))
                    .value();
            if (physicalIndexExists) {
                elasticsearchClient.indices().putAlias(request -> request
                        .index(properties.getPhysicalIndex())
                        .name(properties.getIndexAlias())
                        .isWriteIndex(true));
                ensureParentChildMapping(properties.getIndexAlias());
                log.info("Elasticsearch 知识索引别名已修复: index={}, alias={}",
                        properties.getPhysicalIndex(), properties.getIndexAlias());
                return;
            }
            elasticsearchClient.indices().create(request -> request
                    .index(properties.getPhysicalIndex())
                    .aliases(properties.getIndexAlias(), alias -> alias.isWriteIndex(true))
                    .mappings(mapping -> mapping
                            .properties("chunkId", field -> field.keyword(keyword -> keyword))
                            .properties("fileId", field -> field.keyword(keyword -> keyword))
                            .properties("ragName", field -> field.text(text -> text
                                    .analyzer("cjk")
                                    .fields("keyword", keyword -> keyword.keyword(value -> value))))
                            .properties("knowledgeTag", field -> field.text(text -> text
                                    .analyzer("cjk")
                                    .fields("keyword", keyword -> keyword.keyword(value -> value))))
                            .properties("filename", field -> field.text(text -> text
                                    .analyzer("cjk")
                                    .fields("keyword", keyword -> keyword.keyword(value -> value))))
                            .properties("sourceType", field -> field.keyword(keyword -> keyword))
                            .properties("sheetName", field -> field.text(text -> text
                                    .analyzer("cjk")
                                    .fields("keyword", keyword -> keyword.keyword(value -> value))))
                            .properties("sectionId", field -> field.keyword(keyword -> keyword))
                            .properties("parentId", field -> field.keyword(keyword -> keyword))
                            .properties("chunkType", field -> field.keyword(keyword -> keyword))
                            .properties("rowIndex", field -> field.integer(integer -> integer))
                            .properties("chunkIndex", field -> field.integer(integer -> integer))
                            .properties("content", field -> field.text(text -> text.analyzer("cjk")))
                            .properties("metadata", field -> field.flattened(flattened -> flattened))
                            .properties("embedding", field -> field.denseVector(vector -> vector
                                    .dims(properties.getDimensions())
                                    .index(true)
                                    .similarity(DenseVectorSimilarity.Cosine)))));
            log.info("Elasticsearch 知识索引初始化完成: index={}, alias={}",
                    properties.getPhysicalIndex(), properties.getIndexAlias());
        } catch (Exception e) {
            throw new IllegalStateException("Elasticsearch 知识索引初始化失败", e);
        }
    }

    private void ensureParentChildMapping(String index) throws Exception {
        elasticsearchClient.indices().putMapping(request -> request
                .index(index)
                .properties("parentId", field -> field.keyword(keyword -> keyword))
                .properties("chunkType", field -> field.keyword(keyword -> keyword)));
    }
}
