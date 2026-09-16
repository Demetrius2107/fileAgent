package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import reactor.core.publisher.Flux;

/**
 * Agent 运行时端口（由 fileagent-agent 的 infrastructure 实现）。
 * <p>
 * 提供运行、查询和取消能力。输入是服务端可信的 {@link AgentRunCommand}，
 * 返回受控事件流（不含思维链、工具正文、密钥或异常堆栈）。
 */
public interface AgentRuntimePort {

    /**
     * 启动一次 Agent Run，返回受控事件流。
     *
     * @param command 可信运行命令
     * @return SSE 事件流（run.started … run.completed / run.failed）
     */
    Flux<AgentRunEvent> run(AgentRunCommand command);

    /**
     * 查询运行状态快照。
     *
     * @param runId 运行 ID
     * @return 快照（不存在时抛 {@code BizException(404)}）
     */
    AgentRunSnapshot snapshot(String runId);

    /**
     * 取消运行（幂等）。
     *
     * @param runId 运行 ID
     * @return 取消后的快照（不存在时抛 {@code BizException(404)}）
     */
    AgentRunSnapshot cancel(String runId);
}
