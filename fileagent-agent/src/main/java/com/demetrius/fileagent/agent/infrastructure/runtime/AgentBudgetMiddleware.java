package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * @author raosaijie
 */
public class AgentBudgetMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext runtimeContext,
                                        ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        AgentToolContext toolContext = runtimeContext.get(AgentToolContext.class);
        if (toolContext == null) {
            return next.apply(input);
        }
        AgentRun run = toolContext.run();
        boolean tokenBudgetReached = run.totalTokens() >= toolContext.budget().maxTotalTokens();
        if (tokenBudgetReached) {
            run.addBudgetReason("TOKEN_BUDGET_EXHAUSTED");
        }
        boolean lastAllowedCall = run.modelCallCount() + 1 >= toolContext.budget().maxModelCalls();
        ModelCallInput constrainedInput = (tokenBudgetReached || lastAllowedCall)
                ? new ModelCallInput(input.messages(), List.of(), input.options(), input.model())
                : input;
        return next.apply(constrainedInput)
                .doOnNext(event -> recordUsage(run, event));
    }

    private void recordUsage(AgentRun run, AgentEvent event) {
        if (!(event instanceof ModelCallEndEvent end)) {
            return;
        }
        ChatUsage usage = end.getUsage();
        if (usage != null) {
            run.recordModelUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
        }
    }
}
