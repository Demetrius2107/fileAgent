package com.demetrius.fileagent.agent.domain.checkpoint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;

/**
 * 每个 Run 的最新稳定恢复检查点；只保存恢复必需状态，不承担历史审计职责。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Getter
@Entity
@Table(name = "agent_checkpoint")
public class AgentCheckpointEntity {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "checkpoint_sequence", nullable = false)
    private long checkpointSequence;

    @Lob
    @Column(name = "agentscope_state_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String agentscopeStateCiphertext;

    @Lob
    @Column(name = "authorization_state_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String authorizationStateCiphertext;

    @Lob
    @Column(name = "finalization_context_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String finalizationContextCiphertext;

    @Column(name = "finalization_context_schema_version", nullable = false)
    private int finalizationContextSchemaVersion;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "checksum", nullable = false)
    private String checksum;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentCheckpointEntity() {
    }

    private AgentCheckpointEntity(String runId, long checkpointSequence, String agentscopeStateCiphertext,
                                  String authorizationStateCiphertext, String finalizationContextCiphertext,
                                  int finalizationContextSchemaVersion, int schemaVersion,
                                  String checksum, Instant updatedAt) {
        this.runId = requireText(runId, "runId");
        apply(checkpointSequence, agentscopeStateCiphertext, authorizationStateCiphertext,
                finalizationContextCiphertext, finalizationContextSchemaVersion, schemaVersion, checksum, updatedAt);
    }

    public static AgentCheckpointEntity stable(String runId, long checkpointSequence,
                                               String agentscopeStateCiphertext,
                                               String authorizationStateCiphertext,
                                               String finalizationContextCiphertext,
                                               int finalizationContextSchemaVersion,
                                               int schemaVersion, String checksum, Instant updatedAt) {
        return new AgentCheckpointEntity(runId, checkpointSequence, agentscopeStateCiphertext,
                authorizationStateCiphertext, finalizationContextCiphertext, finalizationContextSchemaVersion,
                schemaVersion, checksum, updatedAt);
    }

    public void replace(long checkpointSequence, String agentscopeStateCiphertext,
                        String authorizationStateCiphertext, String finalizationContextCiphertext,
                        int finalizationContextSchemaVersion, int schemaVersion,
                        String checksum, Instant updatedAt) {
        if (checkpointSequence <= this.checkpointSequence) {
            throw new IllegalArgumentException("checkpointSequence 必须递增");
        }
        apply(checkpointSequence, agentscopeStateCiphertext, authorizationStateCiphertext,
                finalizationContextCiphertext, finalizationContextSchemaVersion, schemaVersion, checksum, updatedAt);
    }

    private void apply(long checkpointSequence, String agentscopeStateCiphertext,
                       String authorizationStateCiphertext, String finalizationContextCiphertext,
                       int finalizationContextSchemaVersion, int schemaVersion,
                       String checksum, Instant updatedAt) {
        if (checkpointSequence < 1) {
            throw new IllegalArgumentException("checkpointSequence 必须大于 0");
        }
        if (finalizationContextSchemaVersion < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 必须大于 0");
        }
        this.checkpointSequence = checkpointSequence;
        this.agentscopeStateCiphertext = requireText(agentscopeStateCiphertext, "agentscopeStateCiphertext");
        this.authorizationStateCiphertext = requireText(authorizationStateCiphertext,
                "authorizationStateCiphertext");
        this.finalizationContextCiphertext = requireText(finalizationContextCiphertext,
                "finalizationContextCiphertext");
        this.finalizationContextSchemaVersion = finalizationContextSchemaVersion;
        this.schemaVersion = schemaVersion;
        this.checksum = requireText(checksum, "checksum");
        this.updatedAt = requireValue(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static <T> T requireValue(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
