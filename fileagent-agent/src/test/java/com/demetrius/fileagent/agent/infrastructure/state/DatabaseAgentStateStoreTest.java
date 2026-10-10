package com.demetrius.fileagent.agent.infrastructure.state;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据库状态存储的密文落库和严格恢复边界验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
class DatabaseAgentStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistEncryptedAgentStateAndRestoreItForSameRun() {
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        when(repository.findCheckpointByRunId("run-1")).thenReturn(Optional.empty());
        when(repository.save(any(AgentCheckpointEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DatabaseAgentStateStore store = new DatabaseAgentStateStore(repository, crypto());
        AgentState state = AgentState.builder()
                .sessionId("run-1")
                .userId("local-operator")
                .summary("只应在密文内出现的摘要")
                .build();

        store.save("local-operator", "run-1", "agent_state", state);

        ArgumentCaptor<AgentCheckpointEntity> checkpointCaptor = ArgumentCaptor.forClass(AgentCheckpointEntity.class);
        verify(repository).save(checkpointCaptor.capture());
        AgentCheckpointEntity checkpoint = checkpointCaptor.getValue();
        assertThat(checkpoint.getAgentscopeStateCiphertext()).doesNotContain("只应在密文内出现的摘要");

        when(repository.findCheckpointByRunId("run-1")).thenReturn(Optional.of(checkpoint));
        assertThat(store.get("local-operator", "run-1", "agent_state", AgentState.class))
                .hasValueSatisfying(restored -> assertThat(restored.getSummary()).isEqualTo("只应在密文内出现的摘要"));
        assertThat(new StrictAgentStateLoader(store).loadVerified("run-1").getSummary())
                .isEqualTo("只应在密文内出现的摘要");
    }

    @Test
    void shouldRejectMissingOrTamperedCheckpointInsteadOfReturningFreshState() {
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        DatabaseAgentStateStore store = new DatabaseAgentStateStore(repository, crypto());
        StrictAgentStateLoader loader = new StrictAgentStateLoader(store);

        when(repository.findCheckpointByRunId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> loader.loadVerified("missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检查点不存在");

        AgentCheckpointEntity tampered = AgentCheckpointEntity.stable(
                "broken", 1L, "v1:local:tampered", "v1:local:tampered", "v1:local:tampered",
                1, 1, "invalid-hmac", Instant.parse("2026-10-09T08:00:00Z"));
        when(repository.findCheckpointByRunId("broken")).thenReturn(Optional.of(tampered));
        assertThatThrownBy(() -> loader.loadVerified("broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检查点");
    }

    @Test
    void shouldKeepAnonymousAgentScopeBootstrapSlotOutsidePersistentState() {
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        DatabaseAgentStateStore store = new DatabaseAgentStateStore(repository, crypto());

        assertThat(store.get(null, "run-bootstrap", "agent_state", AgentState.class)).isEmpty();
        assertThat(store.getList(null, "run-bootstrap", "legacy_messages", AgentState.class)).isEmpty();
        assertThat(store.exists(null, "run-bootstrap")).isFalse();
        assertThat(store.listSessionIds(null)).isEmpty();
        store.save(null, "run-bootstrap", "agent_state", AgentState.builder().build());
        store.delete(null, "run-bootstrap");
        store.delete(null, "run-bootstrap", "agent_state");

        verifyNoInteractions(repository);
    }

    private EnvelopeCrypto crypto() {
        LocalMasterKeyProvider provider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        return new EnvelopeCrypto(new AesGcmCipher(provider), provider);
    }
}
