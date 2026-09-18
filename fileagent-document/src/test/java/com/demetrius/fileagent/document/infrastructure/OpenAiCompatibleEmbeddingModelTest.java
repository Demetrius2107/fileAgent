package com.demetrius.fileagent.document.infrastructure;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.services.blocking.EmbeddingService;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleEmbeddingModelTest {

    private OpenAIClient client;
    private EmbeddingService embeddingService;
    private OpenAiCompatibleEmbeddingModel model;

    @BeforeEach
    void setUp() {
        client = mock(OpenAIClient.class);
        embeddingService = mock(EmbeddingService.class);
        when(client.embeddings()).thenReturn(embeddingService);
        model = new OpenAiCompatibleEmbeddingModel(
                client,
                MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model("text-embedding-v4").dimensions(2).build(),
                ObservationRegistry.NOOP,
                2);
    }

    @Test
    void shouldReturnVectorWhenProviderDoesNotProvidePromptTokens() {
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        Embedding providerEmbedding = mock(Embedding.class);
        when(providerEmbedding.embedding()).thenReturn(List.of(0.1F, 0.2F));
        when(providerEmbedding.index()).thenReturn(0L);
        when(response.data()).thenReturn(List.of(providerEmbedding));
        when(response.model()).thenReturn("text-embedding-v4");
        when(response.usage()).thenThrow(new IllegalStateException("prompt_tokens is not set"));
        when(embeddingService.create(any())).thenReturn(response);

        EmbeddingResponse actual = model.call(new EmbeddingRequest(List.of("年度目标"), null));

        assertThat(actual.getResult().getOutput()).containsExactly(0.1F, 0.2F);
        assertThat(actual.getMetadata().getModel()).isEqualTo("text-embedding-v4");
        verify(response, never()).usage();
    }

    @Test
    void shouldRejectMissingVectorData() {
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        when(response.data()).thenReturn(List.of());
        when(response.model()).thenReturn("text-embedding-v4");
        when(embeddingService.create(any())).thenReturn(response);

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("年度目标"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少向量");
    }

    @Test
    void shouldRejectUnexpectedVectorDimension() {
        CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
        Embedding providerEmbedding = mock(Embedding.class);
        when(providerEmbedding.embedding()).thenReturn(List.of(0.1F, 0.2F, 0.3F));
        when(providerEmbedding.index()).thenReturn(0L);
        when(response.data()).thenReturn(List.of(providerEmbedding));
        when(response.model()).thenReturn("text-embedding-v4");
        when(embeddingService.create(any())).thenReturn(response);

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("年度目标"), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("维度");
    }
}
