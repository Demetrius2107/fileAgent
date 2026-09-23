package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.run.AgentRunBudget;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * @author raosaijie
 */
class AgentBudgetMiddlewareTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void lastModelCallShouldRemoveToolSchemas() {
        AgentRun run = startedRun();
        AgentRunBudget budget = budget(4);
        run.incrementModelCall();
        run.incrementModelCall();
        run.incrementModelCall();
        AgentToolContext toolContext = toolContext(run, budget);
        RuntimeContext context = runtimeContext(toolContext);
        AtomicReference<ModelCallInput> received = new AtomicReference<>();
        ModelCallInput input = inputWithTools();

        new AgentBudgetMiddleware().onModelCall(mock(Agent.class), context, input, next(received)).blockLast();

        assertThat(received.get().tools()).isEmpty();
    }

    @Test
    void nonLastModelCallShouldKeepToolSchemas() {
        AgentRun run = startedRun();
        AgentRunBudget budget = budget(4);
        run.incrementModelCall();
        AgentToolContext toolContext = toolContext(run, budget);
        RuntimeContext context = runtimeContext(toolContext);
        AtomicReference<ModelCallInput> received = new AtomicReference<>();

        new AgentBudgetMiddleware().onModelCall(mock(Agent.class), context, inputWithTools(), next(received)).blockLast();

        assertThat(received.get().tools()).hasSize(1);
    }

    @Test
    void modelUsageShouldAccumulateAndTokenCapShouldRemoveTools() {
        AgentRun run = startedRun();
        AgentRunBudget budget = budget(4);
        run.recordModelUsage(59990, 10, 60000);
        AgentToolContext toolContext = toolContext(run, budget);
        RuntimeContext context = runtimeContext(toolContext);
        AtomicReference<ModelCallInput> received = new AtomicReference<>();
        Function<ModelCallInput, Flux<AgentEvent>> next = modelInput -> {
            received.set(modelInput);
            return Flux.just(new ModelCallEndEvent("reply-1", new ChatUsage(3, 2, 0)));
        };

        new AgentBudgetMiddleware().onModelCall(mock(Agent.class), context, inputWithTools(), next).blockLast();

        assertThat(received.get().tools()).isEmpty();
        assertThat(run.totalTokens()).isEqualTo(60005);
        assertThat(run.budgetReasons()).contains("TOKEN_BUDGET_EXHAUSTED");
    }

    private AgentRun startedRun() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        return run;
    }

    private AgentRunBudget budget(int maxModelCalls) {
        return new AgentRunBudget(8, maxModelCalls, 4000, 12000, 8000, 8000,
                2000, 6000, 500, 50, 3, 60000, Duration.ofSeconds(45));
    }

    private AgentToolContext toolContext(AgentRun run, AgentRunBudget budget) {
        return new AgentToolContext(run, budget, null, null, null, KnowledgeScope.global());
    }

    private RuntimeContext runtimeContext(AgentToolContext context) {
        return RuntimeContext.builder().sessionId("1").userId("server")
                .put(AgentToolContext.class, context).build();
    }

    private ModelCallInput inputWithTools() {
        return new ModelCallInput(List.<Msg>of(), List.of(mock(ToolSchema.class)),
                GenerateOptions.builder().build(), mock(Model.class));
    }

    private Function<ModelCallInput, Flux<AgentEvent>> next(AtomicReference<ModelCallInput> received) {
        return modelInput -> {
            received.set(modelInput);
            return Flux.just(new ModelCallEndEvent("reply-1", new ChatUsage(1, 1, 0)));
        };
    }
}
