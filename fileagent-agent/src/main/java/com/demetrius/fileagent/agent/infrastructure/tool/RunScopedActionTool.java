package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.api.enums.PolicyDecision;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 绑定单个 Run 的副作用工具基类：预检、审批和实际执行必须使用同一可信动作快照。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public abstract class RunScopedActionTool extends ToolBase {

    private final String runId;
    private final AgentActionPolicy actionPolicy;
    private final ConcurrentMap<String, ActionBinding> checkedBindings = new ConcurrentHashMap<>();

    protected RunScopedActionTool(String runId, ToolBase.Builder builder, AgentActionPolicy actionPolicy) {
        super(builder);
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        this.runId = runId;
        this.actionPolicy = Objects.requireNonNull(actionPolicy, "actionPolicy 不能为空");
    }

    /**
     * 先校验 Schema，再由子类从 Run 专属可信上下文构造候选，最后才交给风险策略判断。
     */
    @Override
    public final Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState ignored) {
        return Mono.fromCallable(() -> {
                    AgentActionCandidate candidate = buildCheckedCandidate(input, null);
                    PolicyDecision decision = actionPolicy.decide(candidate);
                    if (decision != PolicyDecision.DENY) {
                        checkedBindings.put(candidate.canonicalInputHmac(), ActionBinding.from(candidate));
                    }
                    return toPermissionDecision(decision);
                })
                .onErrorReturn(PermissionDecision.deny("动作参数或授权范围不合法"));
    }

    /**
     * AgentScope 已批准 ASK 调用后才会进入此方法；仍需重新构造并校验绑定，避免等待期间配置漂移。
     */
    @Override
    public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> executeAfterRecheck(param));
    }

    /**
     * 子类执行工具输入的 Schema 校验。这里不能依赖模型描述，因为执行前必须再次校验。
     */
    protected abstract void validateSchema(Map<String, Object> input);

    /**
     * 使用 Run 专属可信配置和模型参数构造候选。预检阶段 {@code toolCallId} 为 null；执行阶段为原始调用 ID。
     */
    protected abstract AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId);

    /**
     * 仅在权限、Run 归属和不可变绑定全部复核后执行实际副作用。
     */
    protected abstract ToolResultBlock executeVerified(AgentActionCandidate candidate);

    protected final String runId() {
        return runId;
    }

    private ToolResultBlock executeAfterRecheck(ToolCallParam param) {
        if (param == null || param.getToolUseBlock() == null) {
            throw new IllegalArgumentException("动作工具缺少原始 ToolCall");
        }
        validateRuntimeScope(param.getRuntimeContext());
        AgentActionCandidate candidate = buildCheckedCandidate(param.getInput(), param.getToolUseBlock().getId());
        if (candidate.toolCallId() == null || candidate.toolCallId().isBlank()
                || !candidate.toolCallId().equals(param.getToolUseBlock().getId())) {
            throw new IllegalStateException("动作工具调用标识不匹配");
        }
        PolicyDecision decision = actionPolicy.decide(candidate);
        if (decision == PolicyDecision.DENY) {
            throw new IllegalStateException("动作授权范围不合法");
        }
        ActionBinding expected = checkedBindings.get(candidate.canonicalInputHmac());
        if (expected == null || !expected.matches(candidate)) {
            throw new IllegalStateException("动作绑定已变化，拒绝执行");
        }
        if (decision == PolicyDecision.ASK && param.getToolUseBlock().getState() != ToolCallState.ALLOWED) {
            throw new IllegalStateException("动作尚未获得用户批准");
        }
        try {
            return Objects.requireNonNull(executeVerified(candidate), "工具执行结果不能为空");
        } finally {
            // 一次 ToolCall 无论成功或失败都不得由内存绑定再次触发，未知外部结果交给后续审计收敛。
            checkedBindings.remove(candidate.canonicalInputHmac(), expected);
        }
    }

    private AgentActionCandidate buildCheckedCandidate(Map<String, Object> input, String toolCallId) {
        Map<String, Object> safeInput = input == null ? Map.of() : Map.copyOf(input);
        validateSchema(safeInput);
        AgentActionCandidate candidate = buildCandidate(safeInput, toolCallId);
        if (candidate == null || !runId.equals(candidate.runId()) || !getName().equals(candidate.toolName())) {
            throw new IllegalArgumentException("动作候选与当前 Run 或工具不匹配");
        }
        return candidate;
    }

    private void validateRuntimeScope(RuntimeContext context) {
        if (context == null || !runId.equals(context.getSessionId())) {
            throw new IllegalStateException("动作工具不能跨 Run 执行");
        }
    }

    private static PermissionDecision toPermissionDecision(PolicyDecision decision) {
        return switch (decision) {
            case ALLOW -> PermissionDecision.allow("服务端策略允许本次操作");
            case DENY -> PermissionDecision.deny("服务端策略拒绝本次操作");
            case ASK -> PermissionDecision.ask("本次操作需要用户确认");
        };
    }

    private record ActionBinding(String toolVersion, String toolDefinitionVersion,
                                 String endpointRevision, String endpointConfigHash,
                                 String policyVersion, String resourceScope,
                                 String canonicalInputHmac, String operationId,
                                 String safeSummary, String expectedImpact,
                                 String parameterPreview) {

        private static ActionBinding from(AgentActionCandidate candidate) {
            return new ActionBinding(candidate.toolVersion(), candidate.toolDefinitionVersion(),
                    candidate.endpointRevision(), candidate.endpointConfigHash(), candidate.policyVersion(),
                    candidate.resourceScope(), candidate.canonicalInputHmac(), candidate.operationId(),
                    candidate.safeSummary(), candidate.expectedImpact(), candidate.parameterPreview());
        }

        private boolean matches(AgentActionCandidate candidate) {
            return toolVersion.equals(candidate.toolVersion())
                    && toolDefinitionVersion.equals(candidate.toolDefinitionVersion())
                    && endpointRevision.equals(candidate.endpointRevision())
                    && endpointConfigHash.equals(candidate.endpointConfigHash())
                    && policyVersion.equals(candidate.policyVersion())
                    && resourceScope.equals(candidate.resourceScope())
                    && canonicalInputHmac.equals(candidate.canonicalInputHmac())
                    && operationId.equals(candidate.operationId())
                    && safeSummary.equals(candidate.safeSummary())
                    && expectedImpact.equals(candidate.expectedImpact())
                    && parameterPreview.equals(candidate.parameterPreview());
        }
    }
}
