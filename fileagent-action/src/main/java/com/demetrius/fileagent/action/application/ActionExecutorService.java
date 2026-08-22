package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.api.dto.ActionDto;

/**
 * 动作执行应用服务（用例契约）。
 * 由协作者提供 {@code ActionExecutorServiceImpl} 实现（M2 起多动作执行）。
 */
public interface ActionExecutorService {

    /** 分发并执行动作，返回结果对象或 null（仅记录） */
    Object execute(ActionDto action);
}
