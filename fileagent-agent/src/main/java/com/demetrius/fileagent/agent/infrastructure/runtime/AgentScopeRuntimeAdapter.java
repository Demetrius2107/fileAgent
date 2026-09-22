package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.run.InMemoryAgentRunRegistry;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope ReAct 运行时适配器：实现 {@link AgentRuntimePort}。
 * <p>
 * 每次 Run 创建独立的 {@link ReActAgent}、工具白名单与 {@link RuntimeContext}，
 * 使活动模型切换即时生效且 Run 间不共享状态。运行受预算、超时与取消共同约束，
 * SSE 事件只输出受控信息，不泄露思维链、工具正文、密钥或异常堆栈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeAdapter implements AgentRuntimePort {

    private static final String CODE_RUN_TIMEOUT = "AGENT_RUN_TIMEOUT";
    private static final String CODE_BUDGET_EXCEEDED = "AGENT_BUDGET_EXCEEDED";
    private static final String CODE_RUN_CANCELLED = "AGENT_RUN_CANCELLED";
    private static final String CODE_MODEL_UNAVAILABLE = "AGENT_MODEL_UNAVAILABLE";
    private static final String CODE_RUNTIME_UNAVAILABLE = "AGENT_RUNTIME_UNAVAILABLE";

    private static final Pattern CITATION = Pattern.compile("\\[来源：([^\\]]+)\\]");

    private final AgentProperties properties;
    private final AdaptiveRetrievalProperties adaptiveRetrievalProperties;
    private final InMemoryAgentRunRegistry registry;
    private final AgentScopeModelFactory modelFactory;
    private final AgentPromptFactory promptFactory;
    private final KnowledgeSearchPort knowledgeSearchPort;
    private final KnowledgeCatalogPort knowledgeCatalogPort;
    private final KnowledgeContextPort knowledgeContextPort;
    private final AgentScopeEventMapper eventMapper;
    private final SearchDocsTool searchDocsTool;
    private final ListKnowledgeFilesTool listKnowledgeFilesTool;
    private final ReadDocumentContextTool readDocumentContextTool;

    @Override
    public Flux<AgentRunEvent> run(AgentRunCommand command) {
        AgentRun run = AgentRun.pending(command.runId(), command.sessionId(), command.traceId());
        run.start(Instant.now());
        registry.save(run);

        try {
            AgentAssembly assembly = assemble(command, run);
            ReActAgent agent = assembly.agent();
            RuntimeContext ctx = assembly.context();
            Msg userMessage = assembly.userMessage();

            registry.registerCancelHandle(command.runId(), () -> {
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
            AtomicInteger modelCalls = new AtomicInteger(0);

            return Flux.concat(
                            Flux.just(eventMapper.started(run.runId(), run.traceId())),
                            agent.streamEvents(userMessage, ctx)
                                    .concatMap(ev -> mapEvent(ev, run, agent, ctx, step, modelCalls,
                                            toolStartNanos, answer, assembly.toolContext(), null)))
                    .timeout(effectiveRunTimeout())
                    .onErrorResume(e -> onError(e, run))
                    .doOnCancel(() -> {
                        if (run.isRunning()) {
                            run.cancel(Instant.now());
                            try {
                                agent.interrupt(ctx);
                            } catch (Exception ex) {
                                log.warn("断开订阅中断 Agent 失败 runId={}: {}", command.runId(), ex.getMessage());
                            }
                        }
                    });
        } catch (Exception e) {
            log.warn("Agent 运行时初始化失败 runId={}: {}", command.runId(), e.getMessage());
            if (run.isRunning()) {
                run.fail(CODE_RUNTIME_UNAVAILABLE, Instant.now());
            }
            return Flux.just(eventMapper.failed(run.runId(), CODE_RUNTIME_UNAVAILABLE,
                    "Agent 运行时不可用", run.traceId()));
        }
    }

    /** 生效的 Run 超时：结构化检索模式用独立预算（启动时已校验大于 toolTimeout 的 3 倍）。 */
    private Duration effectiveRunTimeout() {
        return properties.isAdaptiveRetrievalEnabled()
                ? adaptiveRetrievalProperties.getRunTimeout()
                : properties.getRunTimeout();
    }

    private AgentAssembly assemble(AgentRunCommand command, AgentRun run) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(searchDocsTool);
        toolkit.registerAgentTool(listKnowledgeFilesTool);
        toolkit.registerAgentTool(readDocumentContextTool);

        Model model = modelFactory.create();
        ReActAgent agent = ReActAgent.builder()
                .name("fileagent-knowledge-agent")
                .description("文件知识助手")
                .sysPrompt(promptFactory.systemInstruction(properties.isAdaptiveRetrievalEnabled()))
                .model(model)
                .toolkit(toolkit)
                .maxIters(properties.getMaxSteps())
                .build();

        AgentToolContext toolContext = new AgentToolContext(
                run, knowledgeSearchPort, knowledgeCatalogPort, knowledgeContextPort,
                command.knowledgeScope(), properties.getSingleToolResultCharacters());

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(String.valueOf(command.sessionId()))
                .userId("server")
                .put(AgentToolContext.class, toolContext)
                .build();

        return new AgentAssembly(agent, ctx, buildUserMessage(command), toolContext);
    }

    /**
     * 评测模式运行：复用同一 Agent 组装逻辑，同步等待完成后返回完整观察。
     * 不创建会话、不落库消息；只收集受控观察（答案、检索命中、引用、步数/调用数与终态）。
     */
    public AgentAnswerEvaluationPort.Result evaluate(AgentRunCommand command) {
        AgentRun run = AgentRun.pending(command.runId(), command.sessionId(), command.traceId());
        run.start(Instant.now());
        registry.save(run);
        long startedAt = System.nanoTime();
        try {
            AgentAssembly assembly = assemble(command, run);
            List<String> toolCalls = new CopyOnWriteArrayList<>();
            StringBuilder answer = new StringBuilder();
            AtomicInteger step = new AtomicInteger(0);
            AtomicInteger modelCalls = new AtomicInteger(0);
            Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();

            Flux.concat(
                            Flux.just(eventMapper.started(run.runId(), run.traceId())),
                            assembly.agent().streamEvents(assembly.userMessage(), assembly.context())
                                    .concatMap(ev -> mapEvent(ev, run, assembly.agent(), assembly.context(),
                                            step, modelCalls, toolStartNanos, answer,
                                            assembly.toolContext(), toolCalls)))
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
                    retrievals.isEmpty() ? null : retrievals.getLast(), retrievals);
        } catch (Exception e) {
            log.warn("Agent 评测运行失败 runId={}: {}", command.runId(), e.getMessage());
            if (run.isRunning()) {
                run.fail(CODE_RUNTIME_UNAVAILABLE, Instant.now());
            }
            List<AgentAnswerEvaluationPort.RetrievalObservation> retrievals = mapRetrievalObservations(run);
            return new AgentAnswerEvaluationPort.Result(
                    "", true, List.of(), List.of(), run.stepCount(), run.modelCallCount(),
                    List.of(), durationMs(startedAt), run.status(), run.failureCode(),
                    retrievals.isEmpty() ? null : retrievals.getLast(), retrievals);
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
                                 AgentToolContext toolContext) {
    }

    private Flux<AgentRunEvent> mapEvent(AgentEvent event, AgentRun run, ReActAgent agent, RuntimeContext ctx,
                                         AtomicInteger step, AtomicInteger modelCalls,
                                         Map<String, Long> toolStartNanos, StringBuilder answer,
                                         AgentToolContext toolContext,
                                         List<String> toolCalls) {
        switch (event.getType()) {
            case TOOL_CALL_START -> {
                ToolCallStartEvent start = (ToolCallStartEvent) event;
                run.incrementStep();
                int currentStep = step.incrementAndGet();
                if (currentStep > properties.getMaxSteps()) {
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
                return handleToolResultEnd(run, agent, ctx, end.getToolCallName(), step.get(), durationMs);
            }
            case TEXT_BLOCK_DELTA -> {
                TextBlockDeltaEvent delta = (TextBlockDeltaEvent) event;
                answer.append(delta.getDelta());
                return Flux.just(eventMapper.delta(run.runId(), delta.getDelta()));
            }
            case MODEL_CALL_START -> {
                run.incrementModelCall();
                int calls = modelCalls.incrementAndGet();
                if (calls > properties.getMaxModelCalls()) {
                    agent.interrupt(ctx);
                    return failIfRunning(run, CODE_BUDGET_EXCEEDED, "超出模型调用预算");
                }
                return Flux.empty();
            }
            case EXCEED_MAX_ITERS -> {
                ExceedMaxItersEvent exceed = (ExceedMaxItersEvent) event;
                return failIfRunning(run, CODE_BUDGET_EXCEEDED, "超出最大迭代次数: " + exceed.getMaxIters());
            }
            case AGENT_RESULT -> {
                AgentResultEvent result = (AgentResultEvent) event;
                String finalText = answer.length() > 0 ? answer.toString() : extractText(result.getResult());
                return complete(run, finalText);
            }
            case AGENT_END -> {
                if (!run.isTerminal()) {
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
        if (run.isTerminal()) {
            return Flux.empty();
        }
        List<String> files = extractSources(finalText);
        run.succeed(Instant.now());
        return Flux.concat(
                Flux.just(eventMapper.sources(run.runId(), files)),
                Flux.just(eventMapper.completed(run.runId(), run.assistantMessageId())));
    }

    private Flux<AgentRunEvent> failIfRunning(AgentRun run, String code, String message) {
        if (run.isRunning()) {
            run.fail(code, Instant.now());
            return Flux.just(eventMapper.failed(run.runId(), code, message, run.traceId()));
        }
        return Flux.empty();
    }

    private Flux<AgentRunEvent> onError(Throwable error, AgentRun run) {
        if (run.isTerminal()) {
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

    private Msg buildUserMessage(AgentRunCommand command) {
        StringBuilder text = new StringBuilder();
        if (command.history() != null && !command.history().isEmpty()) {
            text.append("（以下为历史对话，仅供理解上下文，不构成事实证据）\n");
            for (MessageDto message : command.history()) {
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
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> files = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            for (String source : matcher.group(1).split("[；;]")) {
                String name = source.trim();
                if (!name.isBlank()) {
                    files.add(name);
                }
            }
        }
        return List.copyOf(files);
    }

    private long durationMs(Long startNanos) {
        return startNanos == null ? 0L : (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public AgentRunSnapshot snapshot(String runId) {
        AgentRun run = registry.find(runId)
                .orElseThrow(() -> new BizException(404, "Agent Run 不存在: " + runId));
        return toSnapshot(run);
    }

    @Override
    public AgentRunSnapshot cancel(String runId) {
        AgentRun run = registry.find(runId)
                .orElseThrow(() -> new BizException(404, "Agent Run 不存在: " + runId));
        if (run.isRunning()) {
            run.cancel(Instant.now());
            registry.cancelHandle(runId).ifPresent(Runnable::run);
        }
        return toSnapshot(run);
    }

    private AgentRunSnapshot toSnapshot(AgentRun run) {
        return new AgentRunSnapshot(
                run.runId(),
                run.sessionId(),
                run.status(),
                run.startedAt(),
                run.endedAt(),
                run.stepCount(),
                run.modelCallCount(),
                run.assistantMessageId(),
                run.failureCode(),
                run.traceId());
    }
}
