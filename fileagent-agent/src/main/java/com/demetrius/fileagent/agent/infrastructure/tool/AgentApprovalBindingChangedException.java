package com.demetrius.fileagent.agent.infrastructure.tool;

/**
 * 审批等待期间工具定义、资源范围或规范化参数发生变化时抛出，禁止按旧批准继续执行。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public class AgentApprovalBindingChangedException extends IllegalStateException {

    public AgentApprovalBindingChangedException(String message) {
        super(message);
    }
}
