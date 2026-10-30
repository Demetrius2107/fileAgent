package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.action.infrastructure.sandbox.SandboxClient;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Python Sandbox 执行账本的幂等、CAS 和未知结果收敛测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class PythonSandboxActionExecutionServiceTest {

    private static final String CANONICAL_ARGUMENTS = "{\"code\":\"print(input_data['x'])\",\"input\":{\"x\":3}}";

    private ActionToolExecutionRepository repository;
    private SandboxClient sandboxClient;
    private EnvelopeCrypto crypto;
    private PythonSandboxActionExecutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActionToolExecutionRepository.class);
        sandboxClient = mock(SandboxClient.class);
        crypto = mock(EnvelopeCrypto.class);
        when(crypto.hmac(anyString())).thenAnswer(invocation -> "hmac-" + invocation.<String>getArgument(0).hashCode());
        when(crypto.encrypt(anyString(), anyString())).thenReturn("ciphertext");
        service = new PythonSandboxActionExecutionService(repository, sandboxClient, crypto, new ObjectMapper());
    }

    @Test
    void shouldExecuteOnlyAfterCasAndPersistControlledResult() {
        arrangeNewExecution();
        when(sandboxClient.execute(anyString(), eq("print(input_data['x'])"), eq("{\"x\":3}")))
                .thenReturn(new SandboxExecutionResponse("SUCCEEDED", "3\n", "", null));

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(result.resultSummary()).contains("3");
        verify(sandboxClient).execute(anyString(), eq("print(input_data['x'])"), eq("{\"x\":3}"));
        verify(repository, times(2)).save(any(ActionToolExecutionEntity.class));
    }

    @Test
    void shouldReuseSameSemanticOperationWithoutCallingWorkerAgain() {
        ActionToolExecutionEntity existing = executing("execution-existing", command());
        existing.markOutcomeUnknown("SANDBOX_WORKER_UNAVAILABLE", Instant.parse("2026-10-27T04:00:01Z"));
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.of(existing));

        ActionExecutionView result = service.execute(command());

        assertThat(result.executionId()).isEqualTo("execution-existing");
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        verify(sandboxClient, never()).execute(anyString(), anyString(), anyString());
    }

    @Test
    void shouldConvergeToUnknownWhenWorkerResponseCannotBeConfirmed() {
        arrangeNewExecution();
        when(sandboxClient.execute(anyString(), anyString(), anyString()))
                .thenReturn(new SandboxExecutionResponse("FAILED", "", "", "SANDBOX_WORKER_UNAVAILABLE"));

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(result.resultCode()).isEqualTo("SANDBOX_WORKER_UNAVAILABLE");
        assertThat(result.recoverable()).isFalse();
        verify(sandboxClient, times(1)).execute(anyString(), anyString(), anyString());
    }

    @Test
    void shouldConvergeToUnknownAfterWorkerReportsExecutionTimeout() {
        arrangeNewExecution();
        when(sandboxClient.execute(anyString(), anyString(), anyString()))
                .thenReturn(new SandboxExecutionResponse("FAILED", "", "", "SANDBOX_TIMEOUT"));

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(result.resultCode()).isEqualTo("SANDBOX_TIMEOUT");
        verify(sandboxClient, times(1)).execute(anyString(), anyString(), anyString());
    }

    @Test
    void shouldNotCallWorkerWhenAnotherWorkerOwnsPendingExecution() {
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionToolExecutionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.beginPending(anyString(), anyLong(), any(Instant.class))).thenReturn(0);
        when(repository.findByExecutionId(anyString())).thenAnswer(invocation -> Optional.of(
                ActionToolExecutionEntity.pending(invocation.getArgument(0), command(), Instant.now())));

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.PENDING);
        verify(sandboxClient, never()).execute(anyString(), anyString(), anyString());
    }

    private void arrangeNewExecution() {
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionToolExecutionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.beginPending(anyString(), anyLong(), any(Instant.class))).thenReturn(1);
        when(repository.findByExecutionId(anyString())).thenAnswer(invocation -> Optional.of(
                executing(invocation.getArgument(0), command())));
    }

    private static ActionToolExecutionEntity executing(String executionId, ActionExecutionCommand command) {
        ActionToolExecutionEntity entity = ActionToolExecutionEntity.pending(
                executionId, command, Instant.parse("2026-10-27T04:00:00Z"));
        ReflectionTestUtils.setField(entity, "status", ActionExecutionStatus.EXECUTING);
        ReflectionTestUtils.setField(entity, "startedAt", Instant.parse("2026-10-27T04:00:00Z"));
        ReflectionTestUtils.setField(entity, "version", 1L);
        return entity;
    }

    private static ActionExecutionCommand command() {
        return new ActionExecutionCommand(
                "run-1", "tool-call-1", PythonSandboxActionDefinition.TOOL_NAME,
                PythonSandboxActionDefinition.TOOL_VERSION, PythonSandboxActionDefinition.ACTION_ID,
                PythonSandboxActionDefinition.DEFINITION_VERSION, PythonSandboxActionDefinition.ENDPOINT_REVISION,
                PythonSandboxActionDefinition.ENDPOINT_CONFIG_HASH, PythonSandboxActionDefinition.POLICY_VERSION,
                "execution-key", "fingerprint", "operation-1", CANONICAL_ARGUMENTS,
                "hmac-" + CANONICAL_ARGUMENTS.hashCode(), "local-operator", Set.of(
                        PythonSandboxActionDefinition.RESOURCE_SCOPE), PythonSandboxActionDefinition.RESOURCE_SCOPE);
    }
}
