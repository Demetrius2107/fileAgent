package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import java.io.IOException;
import java.util.List;

/**
 * Podman 子进程边界，命令以参数列表传入，禁止通过 shell 解释执行。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@FunctionalInterface
public interface PodmanCommandRunner {

    Process start(List<String> command) throws IOException;
}
