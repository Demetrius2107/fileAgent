package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.action.ActionCancelResult;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;

import java.util.List;
import java.util.Set;

/**
 * 动作执行跨域契约：只暴露定义快照、受控执行状态和取消语义。
 *
 * @author raosaijie
 */
public interface ActionExecutionPort {

    /** 根据服务端注册标识读取无凭证动作定义。 */
    ActionDefinitionView resolveDefinition(String actionId);

    /** 列出当前授权资源范围内的动作定义，不包含连接信息或凭证。 */
    List<ActionDefinitionView> listDefinitions(Set<String> authorizedResourceScopes);

    /** 校验可信命令并通过幂等账本执行动作。 */
    ActionExecutionView execute(ActionExecutionCommand command);

    /** 查询持久化执行状态，用于重启恢复和人工核对。 */
    ActionExecutionView findExecution(String executionId);

    /** 尝试取消动作，并明确返回是否仍可能产生副作用。 */
    ActionCancelResult cancel(String executionId);
}
