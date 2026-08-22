package com.demetrius.fileagent.action.domain;

import com.demetrius.fileagent.api.dto.ActionDto;
import com.demetrius.fileagent.api.enums.ActionType;

/**
 * 动作处理器契约（策略模式）。
 * 每种动作类型一个实现，注册到 {@link com.demetrius.fileagent.action.application.ActionExecutorService}。
 * 由协作者实现（M2 起：export / chart / call_api / execute_script）。
 */
public interface ActionHandler {

    /** 本处理器支持的动作类型 */
    ActionType supportedType();

    /** 执行动作，返回结果对象（由 chat 域透传展示） */
    Object execute(ActionDto action);
}
