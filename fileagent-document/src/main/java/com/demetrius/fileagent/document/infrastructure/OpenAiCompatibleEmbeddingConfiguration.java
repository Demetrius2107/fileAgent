package com.demetrius.fileagent.document.infrastructure;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 注册兼容缺失 usage 字段的 OpenAI embedding 模型。
 *
 * @author raosaijie
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleEmbeddingConfiguration {

    @Bean
    @Primary
    OpenAiCompatibleEmbeddingModel compatibleEmbeddingModel(
            OpenAiCommonProperties common,
            OpenAiEmbeddingProperties embedding,
            ElasticsearchKnowledgeProperties elasticsearch,
            ObjectProvider<ObservationRegistry> observations) {
        ResolvedConnectionProperties connection = OpenAiAutoConfigurationUtil
                .resolveCommonProperties(common, embedding);
        SpringAiOpenAiHttpClient.Builder httpBuilder = SpringAiOpenAiHttpClient.builder();
        if (connection.getTimeout() != null) {
            httpBuilder.timeout(connection.getTimeout());
        }
        if (connection.getProxy() != null) {
            httpBuilder.proxy(connection.getProxy());
        }
        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .httpClient(httpBuilder.build())
                .baseUrl(connection.getBaseUrl())
                .maxRetries(connection.getMaxRetries());
        if (connection.getCredential() != null) {
            clientOptions.credential(connection.getCredential());
        } else if (StringUtils.hasText(connection.getApiKey())) {
            clientOptions.apiKey(connection.getApiKey());
        }
        applyOrganizationAndHeaders(clientOptions, connection);
        return new OpenAiCompatibleEmbeddingModel(
                new OpenAIClientImpl(clientOptions.build()),
                embedding.getMetadataMode(),
                embedding.toOptions(),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP),
                elasticsearch.getDimensions());
    }

    private void applyOrganizationAndHeaders(ClientOptions.Builder clientOptions,
                                             ResolvedConnectionProperties connection) {
        if (StringUtils.hasText(connection.getOrganizationId())) {
            clientOptions.organization(connection.getOrganizationId());
        }
        if (!connection.getCustomHeaders().isEmpty()) {
            Map<String, List<String>> headers = connection.getCustomHeaders().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));
            clientOptions.headers(headers);
        }
    }
}
