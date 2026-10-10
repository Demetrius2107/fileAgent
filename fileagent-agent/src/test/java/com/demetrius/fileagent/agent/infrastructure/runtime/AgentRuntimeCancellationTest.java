package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行取消句柄的隔离测试：浏览器订阅与显式取消不能共用同一生命周期。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
class AgentRuntimeCancellationTest {

    @Test
    void shouldInterruptOnlyWhenExplicitCancellationIsRequested() {
        AgentExecutionHandleRegistry handles = new AgentExecutionHandleRegistry();
        AtomicInteger interruptCount = new AtomicInteger();
        handles.register("run-1", interruptCount::incrementAndGet);

        assertThat(handles.remove("run-1")).isTrue();
        assertThat(interruptCount).hasValue(0);
        assertThat(handles.interrupt("run-1")).isFalse();
    }

    @Test
    void shouldInvokeActiveInterruptHandleAtMostOnce() {
        AgentExecutionHandleRegistry handles = new AgentExecutionHandleRegistry();
        AtomicInteger interruptCount = new AtomicInteger();
        handles.register("run-1", interruptCount::incrementAndGet);

        assertThat(handles.interrupt("run-1")).isTrue();
        assertThat(handles.interrupt("run-1")).isFalse();
        assertThat(interruptCount).hasValue(1);
    }
}
