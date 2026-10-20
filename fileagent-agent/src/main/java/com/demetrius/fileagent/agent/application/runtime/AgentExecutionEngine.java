package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 单次 Agent 执行端口：由协调器在后台线程调用，不拥有排队、租约或 SSE 订阅生命周期。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentExecutionEngine {

    /**
     * 执行已验证的 Run 命令并返回受控运行事件。
     */
    Flux<AgentRunEvent> execute(AgentRunCommand command);

    /**
     * 从已决审批的 ASKING 检查点继续执行。默认拒绝恢复，避免不支持 HITL 的引擎静默按新请求重跑。
     */
    default Flux<AgentRunEvent> resume(AgentRunCommand command, AgentApprovalEntity approval) {
        return Flux.error(new UnsupportedOperationException("当前 Agent 执行引擎不支持审批恢复"));
    }

    /**
     * 进程中断时仅从已校验的 ASKING 检查点重建等待中的审批；没有该检查点时不做推测性恢复。
     */
    default Optional<AgentApprovalView> recoverWaitingApproval(AgentRunCommand command) {
        return Optional.empty();
    }
}
