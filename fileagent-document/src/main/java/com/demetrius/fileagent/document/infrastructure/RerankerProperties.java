package com.demetrius.fileagent.document.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识检索重排模型配置。
 *
 * @author raosaijie
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.reranker")
public class RerankerProperties {

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model = "qwen3-rerank";
    private int candidateTopK = 50;
    private int topN = 12;
}
