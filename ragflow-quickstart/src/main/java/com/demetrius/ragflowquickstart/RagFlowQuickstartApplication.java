package com.demetrius.ragflowquickstart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * RAGFlow Quickstart 独立启动入口，不参与 fileAgent 根 Maven 聚合构建。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
@SpringBootApplication
@EnableConfigurationProperties(RagFlowProperties.class)
public class RagFlowQuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagFlowQuickstartApplication.class, args);
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
