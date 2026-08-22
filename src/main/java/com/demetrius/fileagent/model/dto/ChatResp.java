package com.demetrius.fileagent.model.dto;

/**
 * 聊天响应（返回给前端的动作 + 展示信息）
 */
public record ChatResp(
        Long messageId,
        ActionDto action
) {
}
