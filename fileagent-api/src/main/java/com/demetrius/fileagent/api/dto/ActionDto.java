package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ActionType;

import java.util.Map;

/**
 * 动作描述（LLM 输出的结构化指令）
 */
public record ActionDto(
        ActionType action,
        Map<String, Object> params,
        String reasoning,
        String summary
) {
}
