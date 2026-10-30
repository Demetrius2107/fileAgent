package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.action.infrastructure.sandbox.SandboxClient;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 副作用账本门禁：语义重复或未知结果只能复用已有事实，禁止重放 Python 脚本。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3ActionLedgerSecurityTest {

    private static final String CANONICAL_ARGUMENTS = "{\"code\":\"print(1)\",\"input\":{}}";

    @Test
    void shouldNotReplayUnknownSemanticOperation() {
        ActionToolExecutionRepository repository = mock(ActionToolExecutionRepository.class);
        SandboxClient sandboxClient = mock(SandboxClient.class);
        EnvelopeCrypto crypto = mock(EnvelopeCrypto.class);
        when(crypto.hmac(anyString())).thenAnswer(invocation -> "hmac-" + invocation.<String>getArgument(0));

        ActionExecutionCommand command = command();
        ActionToolExecutionEntity existing = ActionToolExecutionEntity.pending(
                "existing-execution", command, Instant.now());
        ReflectionTestUtils.setField(existing, "status", ActionExecutionStatus.EXECUTING);
        ReflectionTestUtils.setField(existing, "startedAt", Instant.now());
        existing.markOutcomeUnknown("SANDBOX_TIMEOUT", Instant.now());
        when(repository.findByExecutionKey(command.executionKey())).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint(command.runId(), command.operationFingerprint()))
                .thenReturn(Optional.of(existing));

        PythonSandboxActionExecutionService service = new PythonSandboxActionExecutionService(
                repository, sandboxClient, crypto, new ObjectMapper());
        ActionExecutionView result = service.execute(command);

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(result.resultCode()).isEqualTo("SANDBOX_TIMEOUT");
        verify(sandboxClient, never()).execute(anyString(), anyString(), anyString());
    }

    private static ActionExecutionCommand command() {
        return new ActionExecutionCommand(
                "phase3-run", "tool-call-1", PythonSandboxActionDefinition.TOOL_NAME,
                PythonSandboxActionDefinition.TOOL_VERSION, PythonSandboxActionDefinition.ACTION_ID,
                PythonSandboxActionDefinition.DEFINITION_VERSION, PythonSandboxActionDefinition.ENDPOINT_REVISION,
                PythonSandboxActionDefinition.ENDPOINT_CONFIG_HASH, PythonSandboxActionDefinition.POLICY_VERSION,
                "execution-key", "operation-fingerprint", "operation-id", CANONICAL_ARGUMENTS,
                "hmac-" + CANONICAL_ARGUMENTS, "local-operator",
                Set.of(PythonSandboxActionDefinition.RESOURCE_SCOPE), PythonSandboxActionDefinition.RESOURCE_SCOPE);
    }
}
