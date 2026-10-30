package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 使用 ProcessBuilder 直接启动 Podman，避免把任何动态值拼接给 shell。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
public class ProcessBuilderPodmanCommandRunner implements PodmanCommandRunner {

    @Override
    public Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }
}
