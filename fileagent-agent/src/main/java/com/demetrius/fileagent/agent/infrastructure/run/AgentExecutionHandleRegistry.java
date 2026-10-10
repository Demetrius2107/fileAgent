package com.demetrius.fileagent.agent.infrastructure.run;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前进程中可中断 AgentScope 执行的临时句柄。
 * <p>
 * 此类只保存进程级执行控制，不保存 Run 状态或任何业务事实；浏览器 SSE 的订阅取消不会调用
 * {@link #interrupt(String)}，只有显式取消持久化 Run 后才会触发对应句柄。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@Component
public class AgentExecutionHandleRegistry {

    private final Map<String, Runnable> handles = new ConcurrentHashMap<>();

    /** 注册本进程正在执行的 Run 的中断动作。 */
    public void register(String runId, Runnable handle) {
        requireRunId(runId);
        if (handle == null) {
            throw new IllegalArgumentException("中断句柄不能为空");
        }
        handles.put(runId, handle);
    }

    /** 只执行一次中断动作，防止重复取消多次打断同一个 AgentScope 运行。 */
    public boolean interrupt(String runId) {
        Runnable handle = handles.remove(runId);
        if (handle == null) {
            return false;
        }
        handle.run();
        return true;
    }

    /** 执行正常结束、失败或被协调器收敛后移除句柄。 */
    public boolean remove(String runId) {
        return runId != null && handles.remove(runId) != null;
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
    }
}
