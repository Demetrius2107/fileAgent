package com.demetrius.fileagent.api.port;

/**
 * 评测专用会话生命周期端口：评测 Runner 使用独立临时会话，不能复用或删除用户会话。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public interface EvaluationSessionLifecyclePort {

    /**
     * 创建仅供评测使用的会话。
     *
     * @param title 评测可识别标题
     * @return 新建会话 ID
     */
    Long create(String title);

    /**
     * 删除评测会话及其消息。Agent Run、审批与动作账本仍按各自审计保留策略留存。
     *
     * @param sessionId 评测会话 ID
     */
    void delete(Long sessionId);
}
