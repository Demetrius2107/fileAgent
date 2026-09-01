package com.demetrius.fileagent.document.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ElasticsearchKnowledgeIndexRepository} 测试。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class ElasticsearchKnowledgeIndexRepositoryTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Mock
    private EmbeddingModel embeddingModel;

    private ElasticsearchKnowledgeProperties properties;
    private ElasticsearchKnowledgeIndexRepository repository;

    @BeforeEach
    void setUp() {
        properties = new ElasticsearchKnowledgeProperties();
        properties.setDimensions(2);
        repository = new ElasticsearchKnowledgeIndexRepository(
                elasticsearchClient, embeddingModel, properties);
    }

    @Test
    void saveAllShouldBatchEmbeddingAndBuildDeterministicBulkOperations() throws IOException {
        List<KnowledgeChunk> chunks = List.of(chunk(0), chunk(1));
        when(embeddingModel.embed(List.of("正文0", "正文1")))
                .thenReturn(List.of(new float[]{0.1F, 0.2F}, new float[]{0.3F, 0.4F}));
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        repository.saveAll(chunks);

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(elasticsearchClient).bulk(captor.capture());
        BulkRequest request = captor.getValue();
        assertThat(request.index()).isEqualTo(properties.getIndexAlias());
        assertThat(request.requireAlias()).isTrue();
        assertThat(request.operations()).hasSize(2);
        assertThat(request.operations().get(0).index().id()).isEqualTo("7:0");
        assertThat(request.operations().get(1).index().id()).isEqualTo("7:1");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) request.operations().getFirst().index().document();
        assertThat(source)
                .containsEntry("fileId", "7")
                .containsEntry("content", "正文0")
                .containsEntry("sheetName", "目标")
                .containsEntry("sectionId", "sheet-0-section-0");
        assertThat((float[]) source.get("embedding")).containsExactly(0.1F, 0.2F);
    }

    @Test
    void saveAllShouldRespectEmbeddingProviderBatchLimit() throws IOException {
        List<KnowledgeChunk> chunks = IntStream.range(0, 45)
                .mapToObj(this::chunk)
                .toList();
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> contents = invocation.getArgument(0);
            return contents.stream()
                    .map(content -> new float[]{0.1F, 0.2F})
                    .toList();
        });
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(response);
        repository.saveAll(chunks);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, times(5)).embed(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size)
                .containsExactly(10, 10, 10, 10, 5);

        ArgumentCaptor<BulkRequest> bulkRequest = ArgumentCaptor.forClass(BulkRequest.class);
        verify(elasticsearchClient).bulk(bulkRequest.capture());
        assertThat(bulkRequest.getValue().operations()).hasSize(45);
    }

    @Test
    void saveAllShouldNotEmbedParentChunks() throws IOException {
        KnowledgeChunk child = chunk(0);
        KnowledgeChunk parent = new KnowledgeChunk(
                "7:parent:0",
                7L,
                "年度计划",
                "绩效",
                "目标.xlsx",
                "父块正文",
                2,
                Map.of(
                        "sourceType", "xlsx",
                        "sheetName", "目标",
                        "sectionId", "sheet-0-section-0",
                        "chunkType", "PARENT"));
        when(embeddingModel.embed(List.of("正文0")))
                .thenReturn(List.of(new float[]{0.1F, 0.2F}));
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        repository.saveAll(List.of(child, parent));

        verify(embeddingModel).embed(List.of("正文0"));
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(elasticsearchClient).bulk(captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> parentSource = (Map<String, Object>) captor.getValue()
                .operations().get(1).index().document();
        assertThat(parentSource)
                .containsEntry("chunkType", "PARENT")
                .doesNotContainKey("embedding");
    }

    @Test
    void saveAllShouldRejectEmbeddingCountOrDimensionMismatch() throws IOException {
        List<KnowledgeChunk> chunks = List.of(chunk(0), chunk(1));
        when(embeddingModel.embed(List.of("正文0", "正文1")))
                .thenReturn(List.of(new float[]{0.1F, 0.2F}));

        assertThatThrownBy(() -> repository.saveAll(chunks))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("数量");
        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));

        when(embeddingModel.embed(List.of("正文0")))
                .thenReturn(List.of(new float[]{0.1F}));
        assertThatThrownBy(() -> repository.saveAll(List.of(chunk(0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void saveAllShouldFailWhenAnyBulkItemFails() throws IOException {
        when(embeddingModel.embed(List.of("正文0")))
                .thenReturn(List.of(new float[]{0.1F, 0.2F}));
        BulkResponse response = mock(BulkResponse.class);
        BulkResponseItem failed = mock(BulkResponseItem.class);
        when(response.errors()).thenReturn(true);
        when(response.items()).thenReturn(List.of(failed));
        when(failed.id()).thenReturn("7:0");
        co.elastic.clients.elasticsearch._types.ErrorCause error =
                mock(co.elastic.clients.elasticsearch._types.ErrorCause.class);
        when(failed.error()).thenReturn(error);
        when(error.reason()).thenReturn("mapping error");
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> repository.saveAll(List.of(chunk(0))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("7:0");
    }

    @Test
    void deleteByFileIdShouldUseExactTermFilter() throws IOException {
        repository.deleteByFileId(7L);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(elasticsearchClient).deleteByQuery(captor.capture());
        assertThat(captor.getValue().toString())
                .contains(properties.getIndexAlias(), "fileId", "7");
    }

    private KnowledgeChunk chunk(int index) {
        return new KnowledgeChunk(
                "7:" + index,
                7L,
                "年度计划",
                "绩效",
                "目标.xlsx",
                "正文" + index,
                index,
                Map.of(
                        "sourceType", "xlsx",
                        "sheetName", "目标",
                        "rowIndex", index + 2,
                        "sectionId", "sheet-0-section-0"));
    }
}
