package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import com.demetrius.fileagent.action.infrastructure.http.CanonicalActionArguments;
import com.demetrius.fileagent.action.infrastructure.http.RegisteredHttpActionRegistry;
import com.demetrius.fileagent.action.infrastructure.http.RegisteredHttpExecutor;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动作执行应用服务的幂等复用和未知结果收敛测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class ActionExecutionServiceTest {

    private RegisteredHttpActionRegistry registry;
    private ActionToolExecutionRepository repository;
    private RegisteredHttpExecutor executor;
    private EnvelopeCrypto crypto;
    private ActionExecutionService service;

    @BeforeEach
    void setUp() {
        registry = mock(RegisteredHttpActionRegistry.class);
        repository = mock(ActionToolExecutionRepository.class);
        executor = mock(RegisteredHttpExecutor.class);
        crypto = mock(EnvelopeCrypto.class);
        service = new ActionExecutionService(registry, repository, executor, crypto, new ObjectMapper());
        when(registry.resolveDefinition("endpoint")).thenReturn(definition());
        when(registry.validateArguments("endpoint", Map.of())).thenReturn(
                new CanonicalActionArguments("{}", "input-hmac"));
        when(crypto.hmac("{}")).thenReturn("input-hmac");
    }

    @Test
    void shouldReuseSameSemanticOperationWithoutCallingRemoteAgain() {
        ActionToolExecutionEntity existing = executing("execution-existing", command());
        existing.markOutcomeUnknown("HTTP_OUTCOME_UNKNOWN", Instant.parse("2026-10-20T05:00:01Z"));
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.of(existing));

        ActionExecutionView result = service.execute(command());

        assertThat(result.executionId()).isEqualTo("execution-existing");
        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        verify(executor, never()).execute(anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldPersistEncryptedSuccessResult() {
        arrangeNewExecution();
        String remoteBody = "{\"draftId\":\"draft-1\",\"access_token\":\"secret\"}";
        when(executor.execute(anyString(), anyString(), anyString(), any())).thenReturn(
                new RegisteredHttpExecutor.HttpExecutionResult(
                        ActionExecutionStatus.SUCCEEDED, "HTTP_200", remoteBody,
                        "remote-1", false, 24));
        when(crypto.encrypt(remoteBody,
                "action_tool_execution|execution-new|result_ciphertext|v1")).thenReturn("ciphertext");
        when(crypto.hmac(remoteBody)).thenReturn("result-hmac");

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(result.resultSummary()).contains("draft-1");
        assertThat(result.resultSummary()).contains("[REDACTED]").doesNotContain("secret");
        verify(repository, times(2)).save(any(ActionToolExecutionEntity.class));
    }

    @Test
    void shouldConvergeToOutcomeUnknownWhenRemoteSucceededButResultPersistenceFails() {
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionToolExecutionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new IllegalStateException("simulated commit failure"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.beginPending(anyString(), any(Long.class), any(Instant.class))).thenReturn(1);
        when(repository.findByExecutionId(anyString())).thenAnswer(invocation -> {
            ActionToolExecutionEntity entity = executing("execution-new", command());
            return Optional.of(entity);
        });
        when(executor.execute(anyString(), anyString(), anyString(), any())).thenReturn(
                new RegisteredHttpExecutor.HttpExecutionResult(
                        ActionExecutionStatus.SUCCEEDED, "HTTP_200", "{}", null, false, 2));
        when(crypto.encrypt("{}", "action_tool_execution|execution-new|result_ciphertext|v1"))
                .thenReturn("ciphertext");
        when(crypto.hmac("{}")).thenReturn("input-hmac");

        ActionExecutionView result = service.execute(command());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(result.resultCode()).isEqualTo("HTTP_RESULT_PERSISTENCE_UNKNOWN");
        verify(executor).execute(anyString(), anyString(), anyString(), any());
    }

    private void arrangeNewExecution() {
        when(repository.findByExecutionKey("execution-key")).thenReturn(Optional.empty());
        when(repository.findByRunIdAndOperationFingerprint("run-1", "fingerprint"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ActionToolExecutionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.beginPending(anyString(), any(Long.class), any(Instant.class))).thenReturn(1);
        when(repository.findByExecutionId(anyString()))
                .thenReturn(Optional.of(executing("execution-new", command())));
    }

    private static ActionToolExecutionEntity executing(String executionId, ActionExecutionCommand command) {
        ActionToolExecutionEntity entity = ActionToolExecutionEntity.pending(
                executionId, command, Instant.parse("2026-10-20T05:00:00Z"));
        ReflectionTestUtils.setField(entity, "status", ActionExecutionStatus.EXECUTING);
        ReflectionTestUtils.setField(entity, "startedAt", Instant.parse("2026-10-20T05:00:00Z"));
        ReflectionTestUtils.setField(entity, "version", 1L);
        return entity;
    }

    private static ActionExecutionCommand command() {
        return new ActionExecutionCommand(
                "run-1", "tool-call-1", "call_registered_http_api", "http-action-v1",
                "endpoint", "definition-v1", "endpoint-v1", "config-hmac", "policy-v1",
                "execution-key", "fingerprint", "operation-1", "{}", "input-hmac",
                "local-operator", Set.of("test:write"), "test:write");
    }

    private static ActionDefinitionView definition() {
        return new ActionDefinitionView(
                "endpoint", "测试写动作", "http-action-v1", "definition-v1", "endpoint-v1",
                "config-hmac", "policy-v1", Map.of(), ActionRiskLevel.LOW_RISK_WRITE,
                "test:write", Set.of(), false, "执行测试写动作", "测试服务将收到一次写请求");
    }
}
