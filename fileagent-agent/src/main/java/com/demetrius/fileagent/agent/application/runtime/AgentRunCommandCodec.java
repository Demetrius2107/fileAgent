package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Agent Run 请求的密封持久化编解码，确保恢复执行只使用与 Run 绑定且通过 HMAC 校验的命令。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class AgentRunCommandCodec {

    private final ObjectMapper objectMapper;
    private final EnvelopeCrypto envelopeCrypto;

    /**
     * 序列化、加密并计算 HMAC，供创建 PENDING Run 的事务同时落库。
     */
    public EncodedCommand encrypt(AgentRunCommand command) {
        requireCommand(command);
        String json = serialize(command);
        return new EncodedCommand(envelopeCrypto.encrypt(json, aad(command.runId())), envelopeCrypto.hmac(json));
    }

    /**
     * 解密、校验 HMAC 与 Run 归属；任一步失败均拒绝恢复执行。
     */
    public AgentRunCommand decrypt(AgentRunEntity run) {
        if (run == null) {
            throw new IllegalArgumentException("run 不能为空");
        }
        String json = envelopeCrypto.decrypt(run.getRequestCiphertext(), aad(run.getRunId()));
        String expectedHmac = envelopeCrypto.hmac(json);
        if (!MessageDigest.isEqual(expectedHmac.getBytes(StandardCharsets.UTF_8),
                run.getRequestHmac().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Agent Run 请求完整性校验失败");
        }
        try {
            AgentRunCommand command = objectMapper.readValue(json, AgentRunCommand.class);
            requireCommand(command);
            if (!run.getRunId().equals(command.runId()) || !run.getSessionId().equals(command.sessionId())) {
                throw new IllegalStateException("Agent Run 请求归属不匹配");
            }
            return command;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent Run 请求无法反序列化", e);
        }
    }

    private String serialize(AgentRunCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent Run 请求无法序列化", e);
        }
    }

    private static String aad(String runId) {
        return "agent_run:" + runId + ":request:v1";
    }

    private static void requireCommand(AgentRunCommand command) {
        if (command == null || command.runId() == null || command.runId().isBlank()
                || command.sessionId() == null || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Agent Run 请求不完整");
        }
    }

    /**
     * 供 PENDING Run 持久化的加密请求与完整性摘要。
     */
    public record EncodedCommand(String ciphertext, String hmac) {
    }
}
