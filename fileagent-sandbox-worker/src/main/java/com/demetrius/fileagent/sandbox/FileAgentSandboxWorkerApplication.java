package com.demetrius.fileagent.sandbox;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 独立的 Python Sandbox Worker 启动入口，只接受主应用经 loopback 发起的内部执行请求。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SandboxWorkerProperties.class)
public class FileAgentSandboxWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileAgentSandboxWorkerApplication.class, args);
    }
}
