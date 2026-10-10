package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunCommandCodec;
import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.agent.application.runtime.AgentRunExecutionCoordinator;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Agent Run 准备编排：会话校验 -> 历史加载 -> 保存 USER/PENDING Run -> 提交后入队。
 * <p>
 * 本类在返回 SSE Flux 前已经保存业务事实；浏览器订阅只消费事件，不再拥有后台执行生命周期。
 */
@Service
@RequiredArgsConstructor
public class AgentRunAppServiceImpl implements AgentRunAppService {

    private static final String CODE_FEATURE_DISABLED = "AGENT_FEATURE_DISABLED";
    private static final String CODE_CAPACITY_EXCEEDED = "AGENT_CAPACITY_EXCEEDED";
    private static final String LOCAL_OPERATOR = "local-operator";

    private final SessionQueryPort sessionQueryPort;
    private final SessionMessagePort sessionMessagePort;
    private final AgentRunRepository runRepository;
    private final AgentRunCommandCodec commandCodec;
    private final AgentRunExecutionCoordinator executionCoordinator;
    private final AgentRunEventHub eventHub;
    private final AgentProperties properties;
    private final Clock clock;

    @Value("${fileagent.agent-history-limit:10}")
    private int historyLimit;

    @Override
    @Transactional
    public AgentRunPreparation prepare(Long sessionId, StartAgentRunRequest request, String traceId) {
        if (!properties.isEnabled()) {
            throw new BizException(403, CODE_FEATURE_DISABLED + ": Agent 模式未启用");
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
        int maxPromptCharacters = properties.getMaxPromptCharacters();
        if (maxPromptCharacters > 0 && prompt.codePointCount(0, prompt.length()) > maxPromptCharacters) {
            throw new BizException("AGENT_PROMPT_TOO_LARGE: prompt 超过 " + maxPromptCharacters + " 个字符");
        }

        String runId = UUID.randomUUID().toString();
        if (!executionCoordinator.tryReserveCapacity(runId)) {
            throw new BizException(429, CODE_CAPACITY_EXCEEDED + ": Agent 运行队列已满");
        }
        try {
            // 读取历史须在保存当前 USER 之前，避免当前问题在上下文中出现两次
            String effectiveTraceId = traceId == null || traceId.isBlank() ? "agent-" + runId : traceId;
            AgentRunCommand command;
            if (properties.isAdaptiveRetrievalEnabled()) {
                SessionQueryPort.SessionSummary summary = sessionQueryPort.getSummary(sessionId);
                List<MessageDto> history = summary.throughMessageId() == null
                        ? sessionQueryPort.listMessages(sessionId)
                        : sessionQueryPort.listMessagesAfter(sessionId, summary.throughMessageId());
                command = new AgentRunCommand(
                        runId, sessionId, effectiveTraceId, prompt,
                        summary.content(), summary.throughMessageId(), summary.version(),
                        summary.sourceHash(), history, scope);
            } else {
                List<MessageDto> history = tail(sessionQueryPort.listMessages(sessionId), historyLimit);
                command = new AgentRunCommand(runId, sessionId, effectiveTraceId,
                        prompt, history, scope);
            }
            AgentRunCommandCodec.EncodedCommand encoded = commandCodec.encrypt(command);
            Long inputMessageId = sessionMessagePort.append(sessionId, MessageType.USER, prompt);
            AgentRunEntity run = AgentRunEntity.pending(runId, sessionId, effectiveTraceId, LOCAL_OPERATOR,
                    encoded.ciphertext(), encoded.hmac());
            run.recordInputMessage(inputMessageId, clock.instant());
            AgentRunEntity saved = runRepository.save(run);
            registerEnqueueAfterCommit(command);
            return new AgentRunPreparation(runId, eventHub.subscribe(runId, null,
                    saved.getStatus(), Math.max(0L, saved.getVersion())));
        } catch (RuntimeException e) {
            executionCoordinator.releaseCapacity(runId);
            throw e;
        }
    }

    private void registerEnqueueAfterCommit(AgentRunCommand command) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executionCoordinator.enqueue(command);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executionCoordinator.enqueue(command);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    executionCoordinator.releaseCapacity(command.runId());
                }
            }
        });
    }

    private List<MessageDto> tail(List<MessageDto> messages, int limit) {
        if (messages == null || messages.size() <= limit) {
            return messages == null ? List.of() : messages;
        }
        return messages.subList(messages.size() - limit, messages.size());
    }
}
