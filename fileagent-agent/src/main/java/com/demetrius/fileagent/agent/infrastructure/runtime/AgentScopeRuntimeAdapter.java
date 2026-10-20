package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.application.AgentApprovalService;
import com.demetrius.fileagent.agent.application.AgentCitationService;
import com.demetrius.fileagent.agent.application.AgentHistoryService;
import com.demetrius.fileagent.agent.application.runtime.AgentExecutionEngine;
import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.run.AgentRunBudget;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import com.demetrius.fileagent.agent.infrastructure.state.DatabaseAgentStateStore;
import com.demetrius.fileagent.agent.infrastructure.state.StrictAgentStateLoader;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.GetDocumentOutlineTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import com.demetrius.fileagent.api.dto.AgentCitation;
import com.demetrius.fileagent.agent.infrastructure.tool.AgentActionToolFactory;
import com.demetrius.fileagent.agent.infrastructure.tool.AgentApprovalBindingChangedException;
import com.demetrius.fileagent.agent.infrastructure.tool.RunScopedActionTool;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope ReAct 单次执行引擎：实现 {@link AgentExecutionEngine}。
 * <p>
 * 每次 Run 创建独立的 {@link ReActAgent}、工具白名单与 {@link RuntimeContext}，
 * 使活动模型切换即时生效且 Run 间不共享状态。运行受预算、超时与取消共同约束，
 * SSE 事件只输出受控信息，不泄露思维链、工具正文、密钥或异常堆栈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeAdapter implements AgentExecutionEngine {

    private static final String CODE_RUN_TIMEOUT = "AGENT_RUN_TIMEOUT";
    private static final String CODE_BUDGET_EXCEEDED = "AGENT_BUDGET_EXCEEDED";
    private static final String CODE_RUN_CANCELLED = "AGENT_RUN_CANCELLED";
    private static final String CODE_MODEL_UNAVAILABLE = "AGENT_MODEL_UNAVAILABLE";
    private static final String CODE_RUNTIME_UNAVAILABLE = "AGENT_RUNTIME_UNAVAILABLE";
    private static final String CODE_APPROVAL_PERSIST_FAILED = "AGENT_APPROVAL_PERSIST_FAILED";
    private static final String CODE_TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION =
            "TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION";
    private static final String CODE_APPROVAL_TOOL_UNAVAILABLE = "AGENT_APPROVAL_TOOL_UNAVAILABLE";
    private static final String CODE_APPROVAL_BINDING_CHANGED = "AGENT_APPROVAL_BINDING_CHANGED";
    private static final String CODE_APPROVAL_STATE_INVALID = "AGENT_APPROVAL_STATE_INVALID";

    private final AgentProperties properties;
    private final AdaptiveRetrievalProperties adaptiveRetrievalProperties;
    private final AgentExecutionHandleRegistry executionHandleRegistry;
    private final DatabaseAgentStateStore databaseAgentStateStore;
    private final StrictAgentStateLoader strictAgentStateLoader;
    private final AgentScopeModelFactory modelFactory;
    private final AgentPromptFactory promptFactory;
    private final KnowledgeSearchPort knowledgeSearchPort;
    private final KnowledgeCatalogPort knowledgeCatalogPort;
    private final KnowledgeContextPort knowledgeContextPort;
    private final AgentScopeEventMapper eventMapper;
    private final SearchDocsTool searchDocsTool;
    private final ListKnowledgeFilesTool listKnowledgeFilesTool;
    private final ReadDocumentContextTool readDocumentContextTool;
    private final GetDocumentOutlineTool getDocumentOutlineTool;
    private final AgentHistoryService historyService;
    private final AgentApprovalService approvalService;
    private final List<AgentActionToolFactory> actionToolFactories;
    private final AgentCitationService citationService;

    @Override
    public Flux<AgentRunEvent> execute(AgentRunCommand command) {
        return Flux.defer(() -> executeInIsolatedRuntime(command));
    }

    @Override
    public Flux<AgentRunEvent> resume(AgentRunCommand command, AgentApprovalEntity approval) {
        return Flux.defer(() -> resumeInIsolatedRuntime(command, approval));
    }

    @Override
    public Optional<AgentApprovalView> recoverWaitingApproval(AgentRunCommand command) {
        if (command == null || command.runId() == null || command.runId().isBlank()) {
            throw new IllegalArgumentException("Agent Run 命令不能为空");
        }
        AgentState state = strictAgentStateLoader.loadVerified(command.runId());
        Optional<ToolUseBlock> askingToolCall = findSingleAskingToolCall(state);
        if (askingToolCall.isEmpty()) {
            return Optional.empty();
        }
        RunScopedActionTool actionTool = createActionTools(command, true).get(askingToolCall.get().getName());
        if (actionTool == null) {
            throw new IllegalStateException("审批动作工具不可用");
        }
        return Optional.of(approvalService.pause(command.runId(),
                actionTool.rebuildApprovalCandidate(askingToolCall.get())));
    }

    private Flux<AgentRunEvent> executeInIsolatedRuntime(AgentRunCommand command) {
        AgentRun run = AgentRun.pending(command.runId(), command.sessionId(), command.traceId());
        run.start(Instant.now());

        try {
            AgentHistoryService.PreparedHistory preparedHistory = prepareHistory(run, command);
            AgentAssembly assembly = assemble(command, run, preparedHistory, true);
            ReActAgent agent = assembly.agent();
            RuntimeContext ctx = assembly.context();
            Msg userMessage = assembly.userMessage();

            executionHandleRegistry.register(command.runId(), () -> {
                run.requestCancel();
                try {
                    agent.interrupt(ctx);
                } catch (Exception e) {
                    log.warn("中断 Agent 失败 runId={}: {}", command.runId(), e.getMessage());
                }
            });

            Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();
            StringBuilder answer = new StringBuilder();
            AtomicInteger step = new AtomicInteger(0);
            AtomicReference<PendingApproval> pendingApproval = new AtomicReference<>();

            return Flux.concat(
                            Flux.just(eventMapper.started(run.runId(), run.traceId())),
                            agent.streamEvents(userMessage, ctx)
                                    .concatMap(ev -> mapEvent(ev, run, agent, ctx, step,
                                            toolStartNanos, answer, assembly.toolContext(), assembly.actionTools(),
                                            pendingApproval, null, null)))
                    .timeout(effectiveRunTimeout())
                    .onErrorResume(e -> onError(e, run))
                    .doFinally(signal -> executionHandleRegistry.remove(command.runId()));
        } catch (Exception e) {
            log.warn("Agent 运行时初始化失败 runId={}: {}", command.runId(), e.getMessage());
            if (run.isRunning()) {
                run.fail(CODE_RUNTIME_UNAVAILABLE, Instant.now());
            }
            return Flux.just(eventMapper.failed(run.runId(), CODE_RUNTIME_UNAVAILABLE,
                    "Agent 运行时不可用", run.traceId()));
        }
    }

    /**
     * 使用数据库中原始 ASKING ToolCall 恢复 AgentScope，不接受客户端重写参数或永久权限规则。
     */
    private Flux<AgentRunEvent> resumeInIsolatedRuntime(AgentRunCommand command, AgentApprovalEntity approval) {
        AgentRun run = AgentRun.pending(command.runId(), command.sessionId(), command.traceId());
        run.start(Instant.now());
        try {
            if (approval == null || !command.runId().equals(approval.getRunId())) {
                return failIfRunning(run, CODE_APPROVAL_STATE_INVALID, "审批与当前运行不匹配");
            }
            AgentState state = strictAgentStateLoader.loadVerified(command.runId());
            ToolUseBlock storedToolCall = AgentApprovalService.requireAskingToolCall(
                    state, approval.getToolCallId(), approval.getToolName());
            AgentAssembly assembly = assemble(command, run,
                    new AgentHistoryService.PreparedHistory(null, List.of(), false), true);
            RunScopedActionTool actionTool = assembly.actionTools().get(approval.getToolName());
            if (actionTool == null) {
                return failIfRunning(run, CODE_APPROVAL_TOOL_UNAVAILABLE, "审批动作工具不可用");
            }
            boolean approved = approval.getStatus() == com.demetrius.fileagent.api.enums.ApprovalStatus.APPROVED;
            if (!approved && approval.getStatus() != com.demetrius.fileagent.api.enums.ApprovalStatus.DENIED) {
                return failIfRunning(run, CODE_APPROVAL_STATE_INVALID, "审批尚未完成");
            }
            if (approved) {
                actionTool.restoreApprovedBinding(approval, storedToolCall);
            }
            ToolUseBlock confirmationToolCall = normalizeToolCallContent(storedToolCall);
            ReActAgent agent = assembly.agent();
            RuntimeContext context = assembly.context();
            executionHandleRegistry.register(command.runId(), () -> {
                run.requestCancel();
                try {
                    agent.interrupt(context);
                } catch (Exception e) {
                    log.warn("中断恢复中的 Agent 失败 runId={}: {}", command.runId(), e.getMessage());
                }
            });
            Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();
            StringBuilder answer = new StringBuilder();
            AtomicInteger step = new AtomicInteger(approved ? 1 : 0);
            if (approved) {
                run.incrementStep();
                toolStartNanos.put(storedToolCall.getId(), System.nanoTime());
            }
            AtomicReference<PendingApproval> pendingApproval = new AtomicReference<>();
            Msg confirmation = Msg.builder()
                    .role(MsgRole.USER)
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,
                            List.of(new ConfirmResult(approved, confirmationToolCall, List.of()))))
                    .build();
            return Flux.concat(
                            Flux.just(eventMapper.started(run.runId(), run.traceId())),
                            approved
                                    ? Flux.just(eventMapper.toolStarted(run.runId(), storedToolCall.getName(), 1))
                                    : Flux.empty(),
                            agent.streamEvents(confirmation, context)
                                    .concatMap(event -> mapEvent(event, run, agent, context, step,
                                            toolStartNanos, answer, assembly.toolContext(), assembly.actionTools(),
                                            pendingApproval, approval, null)))
                    .timeout(effectiveRunTimeout())
                    .onErrorResume(error -> {
                        if (approved) {
                            approvalService.failApprovedStep(command.runId(), approval.getApprovalId(),
                                    approval.getToolCallId());
                        }
                        return onError(error, run);
                    })
                    .doFinally(signal -> executionHandleRegistry.remove(command.runId()));
        } catch (AgentApprovalBindingChangedException exception) {
            log.warn("审批动作绑定变化，拒绝恢复 runId={}: {}", command.runId(), exception.getMessage());
            approvalService.invalidateApprovedBinding(command.runId(), approval.getApprovalId());
            return failIfRunning(run, CODE_APPROVAL_BINDING_CHANGED, "审批动作绑定已变化");
        } catch (RuntimeException exception) {
            log.warn("Agent 审批恢复初始化失败 runId={}: {}", command.runId(), exception.getMessage());
            return failIfRunning(run, CODE_APPROVAL_STATE_INVALID, "审批检查点无法恢复");
        }
    }

    /**
     * AgentScope 恢复执行时会用 {@code content} 做工具参数校验；该字段始终由已校验的原始 input 重建，
     * 不信任检查点中可能与 input 不一致的旧 JSON 文本。
     */
    private ToolUseBlock normalizeToolCallContent(ToolUseBlock toolCall) {
        String canonicalContent = JsonUtils.getJsonCodec().toJson(toolCall.getInput());
        if (canonicalContent.equals(toolCall.getContent())) {
            return toolCall;
        }
        return new ToolUseBlock(
                toolCall.getId(),
                toolCall.getName(),
                toolCall.getInput(),
                canonicalContent,
                toolCall.getMetadata(),
                toolCall.getState());
    }

    private Optional<ToolUseBlock> findSingleAskingToolCall(AgentState state) {
        if (state == null || state.getContext() == null) {
            throw new IllegalStateException("Agent 审批检查点不完整");
        }
        List<ToolUseBlock> askingToolCalls = state.getContext().stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .filter(toolCall -> toolCall.getState() == io.agentscope.core.message.ToolCallState.ASKING)
                .toList();
        if (askingToolCalls.isEmpty()) {
            return Optional.empty();
        }
        if (askingToolCalls.size() != 1) {
            throw new IllegalStateException("一次运行不能同时等待多个审批操作");
        }
        return Optional.of(askingToolCalls.getFirst());
    }

    /** 生效的 Run 超时：结构化检索模式用独立预算（启动时已校验大于 toolTimeout 的 3 倍）。 */
    private Duration effectiveRunTimeout() {
        return properties.isAdaptiveRetrievalEnabled()
                ? adaptiveRetrievalProperties.getRunTimeout()
                : properties.getRunTimeout();
    }

    private AgentAssembly assemble(AgentRunCommand command, AgentRun run,
                                   AgentHistoryService.PreparedHistory preparedHistory,
                                   boolean persistAgentScopeState) {
        AgentToolContext toolContext = new AgentToolContext(
                run, currentBudget(), knowledgeSearchPort, knowledgeCatalogPort, knowledgeContextPort,
                command.knowledgeScope());
        Map<String, RunScopedActionTool> actionTools = createActionTools(command, persistAgentScopeState);
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(searchDocsTool);
        toolkit.registerAgentTool(listKnowledgeFilesTool);
        toolkit.registerAgentTool(readDocumentContextTool);
        toolkit.registerAgentTool(getDocumentOutlineTool);
        actionTools.values().forEach(toolkit::registerAgentTool);

        Model model = modelFactory.create();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("fileagent-knowledge-agent")
                .defaultSessionId(command.runId())
                .description("文件知识助手")
                .sysPrompt(promptFactory.systemInstruction(
                        properties.isAdaptiveRetrievalEnabled(), !actionTools.isEmpty()))
                .model(model)
                .toolkit(toolkit)
                .middleware(new AgentBudgetMiddleware())
                .maxIters(properties.getMaxSteps());
        if (persistAgentScopeState) {
            builder.stateStore(databaseAgentStateStore);
        }
        ReActAgent agent = builder.build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(command.runId())
                .userId("local-operator")
                .put(AgentToolContext.class, toolContext)
                .build();

        return new AgentAssembly(agent, ctx, buildUserMessage(command, preparedHistory), toolContext, actionTools);
    }

    /**
     * 评测模式运行：复用同一 Agent 组装逻辑，同步等待完成后返回完整观察。
     * 不创建会话、不落库消息；只收集受控观察（答案、检索命中、引用、步数/调用数与终态）。
     */
    public AgentAnswerEvaluationPort.Result evaluate(AgentRunCommand command) {
        AgentRun run = AgentRun.pending(command.runId(), command.sessionId(), command.traceId());
        run.start(Instant.now());
        long startedAt = System.nanoTime();
        try {
            AgentHistoryService.PreparedHistory preparedHistory = prepareHistory(run, command);
            AgentAssembly assembly = assemble(command, run, preparedHistory, false);
            List<String> toolCalls = new CopyOnWriteArrayList<>();
            StringBuilder answer = new StringBuilder();
            AtomicInteger step = new AtomicInteger(0);
            Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();

            Flux.concat(
                            Flux.just(eventMapper.started(run.runId(), run.traceId())),
                            assembly.agent().streamEvents(assembly.userMessage(), assembly.context())
                                    .concatMap(ev -> mapEvent(ev, run, assembly.agent(), assembly.context(),
                                            step, toolStartNanos, answer,
                                            assembly.toolContext(), assembly.actionTools(),
                                            new AtomicReference<>(), null, toolCalls)))
                    .timeout(effectiveRunTimeout())
                    .onErrorResume(e -> onError(e, run))
                    .blockLast();

            String finalAnswer = answer.toString();
            List<AgentAnswerEvaluationPort.RetrievalObservation> retrievals = mapRetrievalObservations(run);
            return new AgentAnswerEvaluationPort.Result(
                    finalAnswer,
                    finalAnswer.isBlank(),
                    run.retrievedHits(),
                    extractSources(finalAnswer),
                    run.stepCount(),
                    run.modelCallCount(),
                    toolCalls,
                    durationMs(startedAt),
                    run.status(),
                    run.failureCode(),
                    retrievals.isEmpty() ? null : retrievals.getLast(), retrievals,
                    evaluationDetails(run, command, assembly.userMessage(), preparedHistory));
        } catch (Exception e) {
            log.warn("Agent 评测运行失败 runId={}: {}", command.runId(), e.getMessage());
            if (run.isRunning()) {
                run.fail(CODE_RUNTIME_UNAVAILABLE, Instant.now());
            }
            List<AgentAnswerEvaluationPort.RetrievalObservation> retrievals = mapRetrievalObservations(run);
            return new AgentAnswerEvaluationPort.Result(
                    "", true, List.of(), List.of(), run.stepCount(), run.modelCallCount(),
                    List.of(), durationMs(startedAt), run.status(), run.failureCode(),
                    retrievals.isEmpty() ? null : retrievals.getLast(), retrievals,
                    evaluationDetails(run, command, null, null));
        }
    }

    /** 运行态检索溯源映射为评测观察；未调用 search_docs 时为空。 */
    private List<AgentAnswerEvaluationPort.RetrievalObservation> mapRetrievalObservations(AgentRun run) {
        return run.retrievalExecutions().stream().map(execution ->
                new AgentAnswerEvaluationPort.RetrievalObservation(
                        execution.queryType(), execution.strategyId(), execution.plannedQueryCount(),
                        execution.executedQueries(), execution.perQueryHitCount(),
                        execution.candidateChunkIds(), execution.finalChunkIds(),
                        execution.rerankRequested(), execution.rerankApplied(), execution.fallbackCode()))
                .toList();
    }

    private record AgentAssembly(ReActAgent agent, RuntimeContext context, Msg userMessage,
                                 AgentToolContext toolContext,
                                 Map<String, RunScopedActionTool> actionTools) {
    }

    private Flux<AgentRunEvent> mapEvent(AgentEvent event, AgentRun run, ReActAgent agent, RuntimeContext ctx,
                                         AtomicInteger step,
                                         Map<String, Long> toolStartNanos, StringBuilder answer,
                                         AgentToolContext toolContext,
                                         Map<String, RunScopedActionTool> actionTools,
                                         AtomicReference<PendingApproval> pendingApproval,
                                         AgentApprovalEntity resumedApproval,
                                         List<String> toolCalls) {
        switch (event.getType()) {
            case TOOL_CALL_START -> {
                ToolCallStartEvent start = (ToolCallStartEvent) event;
                run.incrementStep();
                int currentStep = step.incrementAndGet();
                if (currentStep > toolContext.budget().maxSteps()) {
                    agent.interrupt(ctx);
                    return failIfRunning(run, CODE_BUDGET_EXCEEDED, "超出步骤预算");
                }
                if (toolCalls != null) {
                    toolCalls.add(start.getToolCallName());
                }
                toolStartNanos.put(start.getToolCallId(), System.nanoTime());
                return Flux.just(eventMapper.toolStarted(run.runId(), start.getToolCallName(), currentStep));
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent end = (ToolResultEndEvent) event;
                long durationMs = durationMs(toolStartNanos.remove(end.getToolCallId()));
                completeApprovedStepIfMatched(run, resumedApproval, end.getToolCallId());
                return handleToolResultEnd(run, agent, ctx, end.getToolCallName(), step.get(), durationMs);
            }
            case TEXT_BLOCK_DELTA -> {
                TextBlockDeltaEvent delta = (TextBlockDeltaEvent) event;
                answer.append(delta.getDelta());
                return Flux.just(eventMapper.delta(run.runId(), delta.getDelta()));
            }
            case MODEL_CALL_START -> {
                run.incrementModelCall();
                int calls = run.modelCallCount();
                if (calls > toolContext.budget().maxModelCalls()) {
                    agent.interrupt(ctx);
                    return failIfRunning(run, CODE_BUDGET_EXCEEDED, "超出模型调用预算");
                }
                return Flux.empty();
            }
            case EXCEED_MAX_ITERS -> {
                ExceedMaxItersEvent exceed = (ExceedMaxItersEvent) event;
                return failIfRunning(run, CODE_BUDGET_EXCEEDED, "超出最大迭代次数: " + exceed.getMaxIters());
            }
            case REQUIRE_USER_CONFIRM -> {
                return captureApproval((RequireUserConfirmEvent) event, run, agent, ctx,
                        actionTools, pendingApproval);
            }
            case AGENT_RESULT -> {
                PendingApproval pending = pendingApproval.getAndSet(null);
                if (pending != null) {
                    return pauseForApproval(run, pending);
                }
                AgentResultEvent result = (AgentResultEvent) event;
                String finalText = answer.length() > 0 ? answer.toString() : extractText(result.getResult());
                return complete(run, finalText);
            }
            case AGENT_END -> {
                PendingApproval pending = pendingApproval.getAndSet(null);
                if (pending != null) {
                    return pauseForApproval(run, pending);
                }
                if (run.isRunning()) {
                    return complete(run, answer.toString());
                }
                return Flux.empty();
            }
            default -> {
                return Flux.empty();
            }
        }
    }

    Flux<AgentRunEvent> handleToolResultEnd(AgentRun run, ReActAgent agent,
                                            RuntimeContext context, String toolName,
                                            int step, long durationMs) {
        String failureCode = run.pendingToolFailureCode();
        if (failureCode != null) {
            agent.interrupt(context);
            return failIfRunning(run, failureCode, "知识库检索暂时不可用");
        }
        return Flux.just(eventMapper.toolCompleted(run.runId(), toolName, step,
                run.lastToolResultCount(), durationMs));
    }

    private Flux<AgentRunEvent> complete(AgentRun run, String finalText) {
        if (!run.isRunning()) {
            return Flux.empty();
        }
        List<String> files = extractSources(finalText);
        // Phase 2C：回答后核验引用并写入三账分离的"最终引用"账；核验失败不阻断收尾
        List<AgentCitation> citations = verifyCitations(run, finalText);
        run.recordCitations(citations);
        run.succeed(Instant.now());
        return Flux.concat(
                Flux.just(eventMapper.sources(run.runId(), files, citations)),
                Flux.just(eventMapper.completed(run.runId(), run.assistantMessageId())));
    }

    private List<AgentCitation> verifyCitations(AgentRun run, String finalText) {
        try {
            return citationService.extractAndVerify(run, finalText);
        } catch (Exception e) {
            log.warn("引用核验失败 runId={}: {}", run.runId(), e.getMessage());
            return List.of();
        }
    }

    private Flux<AgentRunEvent> failIfRunning(AgentRun run, String code, String message) {
        if (run.isRunning()) {
            run.fail(code, Instant.now());
            return Flux.just(eventMapper.failed(run.runId(), code, message, run.traceId()));
        }
        return Flux.empty();
    }

    private Flux<AgentRunEvent> onError(Throwable error, AgentRun run) {
        if (run.isTerminal() || run.isWaitingApproval()) {
            return Flux.empty();
        }
        if (error instanceof TimeoutException) {
            run.timeout(Instant.now());
            return Flux.just(eventMapper.failed(run.runId(), CODE_RUN_TIMEOUT, "运行超时", run.traceId()));
        }
        if (run.isCancelRequested()) {
            run.cancel(Instant.now());
            return Flux.just(eventMapper.failed(run.runId(), CODE_RUN_CANCELLED, "运行已取消", run.traceId()));
        }
        log.warn("Agent 运行失败 runId={}: {}", run.runId(), error.getMessage());
        run.fail(CODE_MODEL_UNAVAILABLE, Instant.now());
        return Flux.just(eventMapper.failed(run.runId(), CODE_MODEL_UNAVAILABLE, "模型调用失败", run.traceId()));
    }

    private Map<String, RunScopedActionTool> createActionTools(AgentRunCommand command,
                                                                 boolean persistAgentScopeState) {
        if (!persistAgentScopeState || !properties.isActionsEnabled()) {
            return Map.of();
        }
        Map<String, RunScopedActionTool> tools = new LinkedHashMap<>();
        for (AgentActionToolFactory factory : actionToolFactories) {
            RunScopedActionTool tool = factory.create(command);
            if (tool == null || tool.getName() == null || tool.getName().isBlank()
                    || tools.putIfAbsent(tool.getName(), tool) != null) {
                throw new IllegalStateException("副作用工具注册不合法或重名");
            }
        }
        return Map.copyOf(tools);
    }

    private Flux<AgentRunEvent> captureApproval(RequireUserConfirmEvent event, AgentRun run,
                                                  ReActAgent agent, RuntimeContext context,
                                                  Map<String, RunScopedActionTool> actionTools,
                                                  AtomicReference<PendingApproval> pendingApproval) {
        if (event.getToolCalls().size() != 1) {
            agent.interrupt(context);
            return failIfRunning(run, CODE_TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION,
                    "一次只能审批一个副作用操作");
        }
        io.agentscope.core.message.ToolUseBlock toolCall = event.getToolCalls().getFirst();
        RunScopedActionTool tool = actionTools.get(toolCall.getName());
        if (tool == null) {
            agent.interrupt(context);
            return failIfRunning(run, CODE_APPROVAL_TOOL_UNAVAILABLE, "审批动作工具不可用");
        }
        try {
            PendingApproval captured = new PendingApproval(tool.approvalCandidate(toolCall));
            if (!pendingApproval.compareAndSet(null, captured)) {
                agent.interrupt(context);
                return failIfRunning(run, CODE_TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION,
                        "一次运行不能同时等待多个审批操作");
            }
            return Flux.empty();
        } catch (RuntimeException exception) {
            log.warn("捕获 Agent 审批动作失败 runId={}: {}", run.runId(), exception.getMessage());
            agent.interrupt(context);
            return failIfRunning(run, CODE_APPROVAL_TOOL_UNAVAILABLE, "审批动作绑定不可用");
        }
    }

    private Flux<AgentRunEvent> pauseForApproval(AgentRun run, PendingApproval pending) {
        try {
            AgentApprovalView approval = approvalService.pause(run.runId(), pending.candidate());
            run.waitForApproval();
            return Flux.just(eventMapper.approvalRequired(run.runId(), approval));
        } catch (RuntimeException exception) {
            log.warn("保存 Agent 审批暂停失败 runId={}: {}", run.runId(), exception.getMessage());
            return failIfRunning(run, CODE_APPROVAL_PERSIST_FAILED, "审批状态保存失败");
        }
    }

    private void completeApprovedStepIfMatched(AgentRun run, AgentApprovalEntity approval, String toolCallId) {
        if (approval == null || approval.getStatus() != com.demetrius.fileagent.api.enums.ApprovalStatus.APPROVED
                || !approval.getToolCallId().equals(toolCallId)) {
            return;
        }
        approvalService.completeApprovedStep(run.runId(), approval.getApprovalId(), toolCallId);
    }

    private record PendingApproval(com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate candidate) {
    }

    private AgentHistoryService.PreparedHistory prepareHistory(AgentRun run, AgentRunCommand command) {
        AgentHistoryService.PreparedHistory prepared = historyService.prepare(run, currentBudget(), command);
        if (prepared != null) {
            return prepared;
        }
        return new AgentHistoryService.PreparedHistory(
                command.historySummary(), command.history(), false);
    }

    private Msg buildUserMessage(AgentRunCommand command,
                                 AgentHistoryService.PreparedHistory preparedHistory) {
        StringBuilder text = new StringBuilder();
        if (preparedHistory.summary() != null && !preparedHistory.summary().isBlank()) {
            text.append("（以下为历史摘要，仅供理解上下文，不构成知识库证据）\n")
                    .append(preparedHistory.summary()).append('\n');
        }
        if (preparedHistory.recentMessages() != null && !preparedHistory.recentMessages().isEmpty()) {
            text.append("（以下为历史对话，仅供理解上下文，不构成事实证据）\n");
            for (MessageDto message : preparedHistory.recentMessages()) {
                String role = message.role() == MessageType.USER ? "用户" : "助手";
                text.append(role).append("：").append(message.content()).append('\n');
            }
            text.append('\n');
        }
        text.append("用户当前问题：").append(command.prompt());
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text.toString()).build())
                .build();
    }

    private String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
    }

    List<String> extractSources(String text) {
        return citationService.extractSourceFilenames(text);
    }

    private long durationMs(Long startNanos) {
        return startNanos == null ? 0L : (System.nanoTime() - startNanos) / 1_000_000;
    }

    private AgentRunBudget currentBudget() {
        return new AgentRunBudget(
                properties.getMaxSteps(),
                properties.isAdaptiveRetrievalEnabled()
                        ? adaptiveRetrievalProperties.getMaxModelCalls()
                        : properties.getMaxModelCalls(),
                properties.getSingleToolResultCharacters(),
                properties.getMaxToolResultCharacters(),
                properties.getMaxPromptCharacters(),
                properties.getMaxHistoryCharacters(),
                properties.getMaxSummaryCharacters(),
                properties.getMaxRecentHistoryCharacters(),
                properties.getSearchSnippetCharacters(),
                properties.getOutlineMaxEntries(),
                properties.getReadMaxChunks(),
                properties.getMaxTotalTokens(),
                effectiveRunTimeout());
    }

    private AgentAnswerEvaluationPort.EvaluationDetails evaluationDetails(AgentRun run,
                                                                            AgentRunCommand command,
                                                                            Msg userMessage,
                                                                            AgentHistoryService.PreparedHistory preparedHistory) {
        AgentRunBudget budget = currentBudget();
        boolean exhausted = run.budgetReasons().stream()
                .anyMatch(reason -> "TOOL_BUDGET_EXHAUSTED".equals(reason)
                        || "TOKEN_BUDGET_EXHAUSTED".equals(reason));
        return new AgentAnswerEvaluationPort.EvaluationDetails(
                run.historyCharacters(),
                run.summaryCharacters(),
                run.searchSnippetCharacters(),
                run.documentReadCharacters(),
                run.toolResultCharacters(),
                run.inputTokens(),
                run.outputTokens(),
                run.totalTokens(),
                run.historyCompressed(),
                run.budgetReasons(),
                promptPreserved(command.prompt(), userMessage),
                run.toolResultCharacters() <= budget.maxToolResultCharacters(),
                exhausted && run.status() == AgentRunStatus.SUCCEEDED,
                false,
                false,
                preparedHistory == null ? null : preparedHistory.summary(),
                preparedHistory == null ? null : preparedHistory.summaryThroughMessageId());
    }

    private boolean promptPreserved(String prompt, Msg userMessage) {
        if (prompt == null || prompt.isBlank() || userMessage == null) {
            return false;
        }
        String text = extractText(userMessage);
        int first = text.indexOf(prompt);
        return first >= 0 && first == text.lastIndexOf(prompt);
    }
}
