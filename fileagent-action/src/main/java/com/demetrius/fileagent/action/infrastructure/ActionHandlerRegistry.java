package com.demetrius.fileagent.action.infrastructure;

import com.demetrius.fileagent.action.domain.ActionHandler;
import com.demetrius.fileagent.api.enums.ActionType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动作处理器注册表（骨架：基于 Spring 容器收集 ActionHandler Bean）。
 * 由协作者在基础设施层组装并暴露给 application 层（M2）。
 */
public class ActionHandlerRegistry {

    private final Map<ActionType, ActionHandler> handlers;

    public ActionHandlerRegistry(List<ActionHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ActionHandler::supportedType, Function.identity()));
    }

    public Optional<ActionHandler> find(ActionType type) {
        return Optional.ofNullable(handlers.get(type));
    }
}
