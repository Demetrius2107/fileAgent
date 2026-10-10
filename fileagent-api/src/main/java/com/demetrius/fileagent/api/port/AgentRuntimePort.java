package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import reactor.core.publisher.Flux;

/**
 * Agent 运行时端口（由 fileagent-agent 的 infrastructure 实现）。
 * <p>
 * 提供已持久化 Run 的查询、订阅和取消能力，返回受控事件流（不含思维链、工具正文、密钥或异常堆栈）。
 */
public interface AgentRuntimePort {

    /**
     * 查询运行状态快照。
     *
     * @param runId 运行 ID
     * @return 快照（不存在时抛 {@code BizException(404)}）
     */
    AgentRunSnapshot snapshot(String runId);

    /**
     * 查询会话中的当前活动 Run；不存在时返回 {@code null}。
     */
    AgentRunSnapshot active(Long sessionId);

    /**
     * 按最后一个已处理事件游标订阅运行事件。
     */
    Flux<AgentRunEvent> events(String runId, String lastEventId);

    /**
     * 取消运行（幂等）。
     *
     * @param runId 运行 ID
     * @return 取消后的快照（不存在时抛 {@code BizException(404)}）
     */
    AgentRunSnapshot cancel(String runId);
}
