package com.demetrius.fileagent.document.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 向量库装配：SimpleVectorStore + JSON 文件持久化。
 * <p>
 * 单机开发/小数据量方案：向量落在 {@code fileagent.vector-store-path} 指定的
 * JSON 文件，启动时自动加载；多实例或大数据量时替换为 PgVectorStore 等外部实现
 * （见 {@code spring-ai-pgvector-store}）。
 */
@Slf4j
@Configuration
public class VectorStoreConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel,
                                         @Value("${fileagent.vector-store-path:./storage/vectorstore.json}") String storePath) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = new File(storePath);
        if (storeFile.exists()) {
            vectorStore.load(storeFile);
            log.info("向量库已加载: {}", storeFile.getAbsolutePath());
        }
        return vectorStore;
    }
}
