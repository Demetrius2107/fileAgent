package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Elasticsearch 真实索引、写入与混合检索集成测试。
 *
 * @author raosaijie
 */
@Testcontainers(disabledWithoutDocker = true)
class ElasticsearchKnowledgeIntegrationTest {

    private static final DockerImageName ELASTICSEARCH_IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.4.2");

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(ELASTICSEARCH_IMAGE)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false");

    private static ElasticsearchClient elasticsearchClient;
    private static ElasticsearchKnowledgeProperties properties;

    @BeforeAll
    static void setUpClient() {
        elasticsearchClient = ElasticsearchClient.of(builder -> builder
                .host("http://" + ELASTICSEARCH.getHttpHostAddress()));
        properties = new ElasticsearchKnowledgeProperties();
        properties.setIndexAlias("fileagent-knowledge-test");
        properties.setPhysicalIndex("fileagent-knowledge-test-v1");
        properties.setDimensions(2);
        properties.setAdjacentWindow(0);
        properties.setFinalTopK(10);
        new ElasticsearchKnowledgeIndexInitializer(elasticsearchClient, properties).initialize();
    }

    @AfterAll
    static void closeClient() throws Exception {
        if (elasticsearchClient != null) {
            elasticsearchClient._transport().close();
        }
    }

    @Test
    void shouldIndexSearchFilterExpandAndOverwriteByDeterministicId() throws Exception {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<KnowledgeChunk> chunks = List.of(
                chunk("1:0", 1L, "年度目标一：完成系统升级", 0, "section-a"),
                chunk("1:1", 1L, "年度目标二：提升交付效率", 1, "section-a"),
                chunk("2:0", 2L, "其他部门年度目标", 0, "section-b"));
        when(embeddingModel.embed(chunks.stream().map(KnowledgeChunk::content).toList()))
                .thenReturn(List.of(
                        new float[]{1.0F, 0.0F},
                        new float[]{0.9F, 0.1F},
                        new float[]{0.8F, 0.2F}));
        ElasticsearchKnowledgeIndexRepository repository =
                new ElasticsearchKnowledgeIndexRepository(elasticsearchClient, embeddingModel, properties);
        repository.saveAll(chunks);

        assertThat(elasticsearchClient.indices()
                .existsAlias(request -> request.name(properties.getIndexAlias())).value()).isTrue();
        assertThat(elasticsearchClient.indices()
                .exists(request -> request.index(properties.getPhysicalIndex())).value()).isTrue();

        when(embeddingModel.embed("年度目标")).thenReturn(new float[]{1.0F, 0.0F});
        KnowledgeSearchPort searchPort = new KnowledgeSearchPortImpl(
                elasticsearchClient, embeddingModel, properties, new RrfFusion());
        List<KnowledgeSearchPort.KnowledgeHit> result = searchPort.search(
                new KnowledgeSearchPort.SearchQuery(
                        "年度目标", "LIST_ALL", null, null, 1L));

        assertThat(result).extracting(KnowledgeSearchPort.KnowledgeHit::chunkId)
                .containsExactly("1:0", "1:1");
        assertThat(result).extracting(KnowledgeSearchPort.KnowledgeHit::fileId)
                .containsOnly(1L);

        KnowledgeChunk replacement = chunk(
                "1:0", 1L, "年度目标一：完成核心系统升级", 0, "section-a");
        when(embeddingModel.embed(List.of(replacement.content())))
                .thenReturn(List.of(new float[]{1.0F, 0.0F}));
        repository.saveAll(List.of(replacement));

        List<KnowledgeSearchPort.KnowledgeHit> overwritten = searchPort.search(
                new KnowledgeSearchPort.SearchQuery(
                        "年度目标", "LIST_ALL", null, null, 1L));
        assertThat(overwritten.getFirst().content()).contains("核心系统升级");
    }

    private static KnowledgeChunk chunk(
            String chunkId, Long fileId, String content, int chunkIndex, String sectionId) {
        return new KnowledgeChunk(
                chunkId,
                fileId,
                "企业目标",
                "绩效",
                "年度目标.xlsx",
                content,
                chunkIndex,
                Map.of(
                        "sourceType", "xlsx",
                        "sheetName", "目标",
                        "rowIndex", chunkIndex + 2,
                        "sectionId", sectionId));
    }
}
