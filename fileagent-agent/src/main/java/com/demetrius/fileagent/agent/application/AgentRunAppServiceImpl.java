package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * Agent 运行编排：会话校验 -> 历史加载 -> 保存 USER -> 启动 Runtime -> 落库 ASSISTANT。
 * <p>
 * 落库只在 run.completed 时发生，失败/取消不落空回答。历史消息仅作文本上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunAppServiceImpl implements AgentRunAppService {

    private static final String CODE_PERSIST_FAILED = "AGENT_PERSIST_FAILED";
    private static final String CODE_FEATURE_DISABLED = "AGENT_FEATURE_DISABLED";

    private final SessionQueryPort sessionQueryPort;
    private final SessionMessagePort sessionMessagePort;
    private final AgentRuntimePort agentRuntimePort;
    private final AgentProperties properties;

    @Value("${fileagent.agent-history-limit:10}")
    private int historyLimit;

    @Override
    public Flux<AgentRunEvent> run(Long sessionId, StartAgentRunRequest request, String traceId) {
        if (!properties.isEnabled()) {
            return Flux.just(AgentRunEvent.failed(null, CODE_FEATURE_DISABLED,
                    "Agent 模式未启用，请先在配置中开启", traceId));
        }
        if (sessionId == null) {
            throw new BizException("sessionId 不能为空");
        }
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new BizException("prompt 不能为空");
        }
        if (!sessionQueryPort.exists(sessionId)) {
            throw new BizException(404, "会话不存在");
        }

        KnowledgeScope scope = request.knowledgeScope() == null ? KnowledgeScope.global() : request.knowledgeScope();
        String prompt = request.prompt().trim();

        return Flux.defer(() -> {
            // 读取历史须在保存当前 USER 之前，避免当前问题在上下文中出现两次
            List<MessageDto> history = tail(sessionQueryPort.listMessages(sessionId), historyLimit);
            sessionMessagePort.append(sessionId, MessageType.USER, prompt);

            String runId = UUID.randomUUID().toString();
            AgentRunCommand command = new AgentRunCommand(runId, sessionId, traceId, prompt, history, scope);

            StringBuilder answer = new StringBuilder();
            return agentRuntimePort.run(command)
                    .concatMap(event -> {
                        if ("message.delta".equals(event.type())) {
                            answer.append(event.content());
                            return Flux.just(event);
                        }
                        if ("run.completed".equals(event.type())) {
                            Long messageId = sessionMessagePort.append(sessionId, MessageType.ASSISTANT, answer.toString());
                            return Flux.just(AgentRunEvent.completed(runId, messageId));
                        }
                        return Flux.just(event);
                    })
                    .onErrorResume(e -> {
                        log.warn("Agent 回答落库失败 runId={}: {}", runId, e.getMessage());
                        return Flux.just(AgentRunEvent.failed(runId, CODE_PERSIST_FAILED,
                                "回答保存失败，请稍后重试", traceId));
                    });
        });
    }

    private List<MessageDto> tail(List<MessageDto> messages, int limit) {
        if (messages == null || messages.size() <= limit) {
            return messages == null ? List.of() : messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }
}
