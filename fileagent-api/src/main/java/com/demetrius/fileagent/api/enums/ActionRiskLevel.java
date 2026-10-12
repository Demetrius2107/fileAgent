package com.demetrius.fileagent.api.enums;

/**
 * Agent 受控动作的风险分级，服务端策略据此决定自动执行、拒绝或发起审批。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public enum ActionRiskLevel {

    /** 只读取已授权资源且不向外部系统发送模型自由文本的操作。 */
    READ_ONLY,
    /** 可回滚或影响范围有限的写操作，默认要求本次人工确认。 */
    LOW_RISK_WRITE,
    /** 影响外部系统、权限或不可逆数据的写操作，必须本次人工确认。 */
    HIGH_RISK_WRITE
}
