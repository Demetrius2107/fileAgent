package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 兼容不返回 OpenAI embedding usage 字段的模型响应。
 *
 * @author raosaijie
 */
public class OpenAiCompatibleEmbeddingModel extends OpenAiEmbeddingModel {

    private final OpenAIClient client;
    private final OpenAiEmbeddingOptions defaultOptions;
    private final int expectedDimensions;

    public OpenAiCompatibleEmbeddingModel(OpenAIClient client,
                                          MetadataMode metadataMode,
                                          OpenAiEmbeddingOptions defaultOptions,
                                          ObservationRegistry observationRegistry,
                                          int expectedDimensions) {
        super(client, metadataMode, defaultOptions, observationRegistry);
        this.client = client;
        this.defaultOptions = defaultOptions;
        this.expectedDimensions = expectedDimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        OpenAiEmbeddingOptions actualOptions = OpenAiEmbeddingOptions.builder()
                .from(defaultOptions)
                .merge(request.getOptions())
                .build();
        CreateEmbeddingResponse response = client.embeddings()
                .create(actualOptions.toOpenAiCreateParams(request.getInstructions()));
        if (response == null) {
            throw new BizException("Embedding 响应为空");
        }
        String model = requiredModel(response);
        List<Embedding> data = response.data();
        if (data == null || data.isEmpty()) {
            throw new BizException("Embedding 响应缺少向量数据");
        }
        List<org.springframework.ai.embedding.Embedding> vectors = data.stream()
                .map(this::toEmbedding)
                .toList();
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(model);
        return new EmbeddingResponse(vectors, metadata);
    }

    private org.springframework.ai.embedding.Embedding toEmbedding(Embedding providerEmbedding) {
        if (providerEmbedding == null) {
            throw new BizException("Embedding 响应缺少向量数据");
        }
        try {
            List<Float> values = providerEmbedding.embedding();
            if (values == null || values.size() != expectedDimensions) {
                throw new BizException("Embedding 响应向量维度与索引配置不一致");
            }
            float[] vector = new float[values.size()];
            for (int index = 0; index < values.size(); index++) {
                vector[index] = values.get(index);
            }
            return new org.springframework.ai.embedding.Embedding(
                    vector, Math.toIntExact(providerEmbedding.index()));
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BizException("Embedding 响应缺少向量数据");
        }
    }

    private String requiredModel(CreateEmbeddingResponse response) {
        try {
            String model = response.model();
            if (StringUtils.hasText(model)) {
                return model;
            }
        } catch (RuntimeException ignored) {
            // SDK 对缺失必填字段会抛异常，统一转换为脱敏业务错误。
        }
        throw new BizException("Embedding 响应缺少 model");
    }
}
