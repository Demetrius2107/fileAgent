package com.demetrius.fileagent.agent.domain.step;

/**
 * Agent Run 中可持久化步骤的类型。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public enum AgentStepType {

    /** 一次模型推理调用。 */
    MODEL_CALL,
    /** 一次服务端登记的工具调用。 */
    TOOL_CALL,
    /** 将最终回答及关联事实原子落库的收尾步骤。 */
    FINALIZATION
}
