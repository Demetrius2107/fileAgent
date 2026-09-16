package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.infrastructure.runtime.AgentScopeRuntimeAdapter;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent 回答评测服务：实现 {@link AgentAnswerEvaluationPort}，以评测模式运行 Agent。
 * <p>
 * 与生产编排（{@code AgentRunAppServiceImpl}）的关键区别：本服务不创建会话、不落库任何消息，
 * 直接复用 {@link AgentScopeRuntimeAdapter#evaluate} 同步运行并收集受控观察。
 * {@link SessionMessagePort} 仅在此显式声明「评测模式禁止写消息」的边界——本服务从不调用它。
 */
@Service
@RequiredArgsConstructor
public class AgentAnswerEvaluationService implements AgentAnswerEvaluationPort {

    private final AgentScopeRuntimeAdapter runtimeAdapter;

    @SuppressWarnings("unused")
    private final SessionMessagePort sessionMessagePort;

    @Override
    public Result evaluate(Query query) {
        AgentRunCommand command = new AgentRunCommand(
                UUID.randomUUID().toString(),
                null,
                "agent-eval",
                query.question(),
                toMessageDtos(query.history()),
                new KnowledgeScope(query.ragName(), query.knowledgeTag()));
        return runtimeAdapter.evaluate(command);
    }

    private static List<MessageDto> toMessageDtos(List<HistoryMessage> history) {
        return history.stream()
                .map(message -> new MessageDto(null, null, message.role(), message.content(), null, null))
                .toList();
    }
}
