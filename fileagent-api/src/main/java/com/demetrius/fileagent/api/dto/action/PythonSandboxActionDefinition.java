package com.demetrius.fileagent.api.dto.action;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python Sandbox 的固定无凭证动作定义，模型不能指定镜像、网络、挂载、身份或 Worker 地址。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public final class PythonSandboxActionDefinition {

    public static final String TOOL_NAME = "execute_python";
    public static final String TOOL_VERSION = "python-sandbox-v1";
    public static final String ACTION_ID = "python-sandbox";
    public static final String DEFINITION_VERSION = "python-sandbox-definition-v1";
    public static final String ENDPOINT_REVISION = "sandbox-worker-v1";
    public static final String ENDPOINT_CONFIG_HASH = "python-sandbox-runtime-policy-v1";
    public static final String POLICY_VERSION = "python-sandbox-policy-v1";
    public static final String RESOURCE_SCOPE = "sandbox:python";

    private PythonSandboxActionDefinition() {
    }

    /** 返回运行级工具、审批和执行账本共同使用的固定动作快照。 */
    public static ActionDefinitionView definition() {
        return new ActionDefinitionView(
                ACTION_ID,
                "执行 Python 脚本",
                TOOL_VERSION,
                DEFINITION_VERSION,
                ENDPOINT_REVISION,
                ENDPOINT_CONFIG_HASH,
                POLICY_VERSION,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "code", Map.of(
                                        "type", "string",
                                        "minLength", 1,
                                        "maxLength", 20 * 1024,
                                        "description", "将在隔离 Sandbox 中运行的 Python 代码"),
                                "input", Map.of(
                                        "type", "object",
                                        "description", "以不可变 input_data 变量提供给脚本的 JSON object")),
                        "required", List.of("code", "input")),
                ActionRiskLevel.HIGH_RISK_WRITE,
                RESOURCE_SCOPE,
                Set.of("code", "input"),
                false,
                "在无网络、根文件系统只读的隔离容器中运行 Python 脚本。",
                "脚本最长运行 10 秒，输出受限；容器临时目录不会持久化，不能访问宿主机资源。"
        );
    }
}
