package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;

/**
 * Python Sandbox 跨域执行契约：只接收已绑定的可信命令，不暴露 Worker 连接或容器控制参数。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public interface PythonSandboxExecutionPort {

    /** 返回固定的无凭证 Sandbox 动作定义。 */
    ActionDefinitionView definition();

    /** 通过幂等账本执行已审批的 Python Sandbox 命令。 */
    ActionExecutionView execute(ActionExecutionCommand command);

    /** 查询持久化执行结果，未知结果只能人工核对，不能自动重放。 */
    ActionExecutionView findExecution(String executionId);
}
