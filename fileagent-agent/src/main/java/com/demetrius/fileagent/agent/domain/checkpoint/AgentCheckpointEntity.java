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

    /** 所属 Agent Run 的业务主键；每个 Run 只保留一份最新稳定检查点。 */
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    /** 检查点单调递增序号，防止较早快照覆盖已经确认的恢复状态。 */
    @Column(name = "checkpoint_sequence", nullable = false)
    private long checkpointSequence;

    /** 以版本化信封加密的 AgentScope 状态，恢复执行前必须完整解密并校验。 */
    @Lob
    @Column(name = "agentscope_state_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String agentscopeStateCiphertext;

    /** 以版本化信封加密的授权状态，恢复后继续沿用原 Run 的审批边界。 */
    @Lob
    @Column(name = "authorization_state_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String authorizationStateCiphertext;

    /** 以版本化信封加密的最终回答事务上下文，避免恢复时重复写入助手消息。 */
    @Lob
    @Column(name = "finalization_context_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String finalizationContextCiphertext;

    /** 最终化上下文的序列化格式版本，用于拒绝不兼容的历史检查点。 */
    @Column(name = "finalization_context_schema_version", nullable = false)
    private int finalizationContextSchemaVersion;

    /** 整个检查点载荷的序列化格式版本，用于严格恢复校验。 */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /** 三类密文载荷及格式版本的完整性校验值，防止损坏数据被当作新状态加载。 */
    @Column(name = "checksum", nullable = false)
    private String checksum;

    /** JPA 乐观锁版本，确保多个恢复协调器不会互相覆盖检查点。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 最近一次成功替换检查点的时间，供恢复扫描和审计使用。 */
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
