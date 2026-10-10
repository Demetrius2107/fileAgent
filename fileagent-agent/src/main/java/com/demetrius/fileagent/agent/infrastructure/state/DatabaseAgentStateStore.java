package com.demetrius.fileagent.agent.infrastructure.state;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointRepository;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 将 AgentScope 状态按 Run 加密保存到检查点，禁止 SDK 状态落入本地文件或内存作为恢复事实。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class DatabaseAgentStateStore implements AgentStateStore {

    private static final String LOCAL_OPERATOR = "local-operator";
    private static final String AGENT_STATE_KEY = "agent_state";
    private static final int SCHEMA_VERSION = 1;
    private static final int FINALIZATION_CONTEXT_SCHEMA_VERSION = 1;

    private final AgentCheckpointRepository checkpointRepository;
    private final EnvelopeCrypto envelopeCrypto;

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        if (isAnonymousBootstrapSlot(userId)) {
            return;
        }
        validateScope(userId, sessionId);
        if (value == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (value instanceof AgentState agentState) {
            validateAgentStateIdentity(agentState, sessionId);
        }
        StateSnapshot current = loadSnapshotOrEmpty(sessionId);
        persist(sessionId, current.withState(requireText(key, "key"), JsonUtils.getJsonCodec().toJson(value)));
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        if (isAnonymousBootstrapSlot(userId)) {
            return;
        }
        validateScope(userId, sessionId);
        if (values == null || values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("state 列表不能为空且不能包含 null");
        }
        StateSnapshot current = loadSnapshotOrEmpty(sessionId);
        List<String> jsonValues = values.stream().map(JsonUtils.getJsonCodec()::toJson).toList();
        persist(sessionId, current.withList(requireText(key, "key"), jsonValues));
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        if (isAnonymousBootstrapSlot(userId)) {
            return Optional.empty();
        }
        validateScope(userId, sessionId);
        requireValue(type, "type");
        return loadSnapshot(sessionId)
                .map(snapshot -> snapshot.stateJsonByKey().get(key))
                .filter(json -> json != null && !json.isBlank())
                .map(json -> decode(json, type));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        if (isAnonymousBootstrapSlot(userId)) {
            return List.of();
        }
        validateScope(userId, sessionId);
        requireValue(itemType, "itemType");
        return loadSnapshot(sessionId)
                .map(snapshot -> snapshot.listJsonByKey().get(key))
                .map(values -> values.stream().map(json -> decode(json, itemType)).toList())
                .orElseGet(List::of);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        if (isAnonymousBootstrapSlot(userId)) {
            return false;
        }
        validateScope(userId, sessionId);
        return checkpointRepository.findCheckpointByRunId(sessionId).isPresent();
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (isAnonymousBootstrapSlot(userId)) {
            return;
        }
        validateScope(userId, sessionId);
        checkpointRepository.deleteByRunId(sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        if (isAnonymousBootstrapSlot(userId)) {
            return;
        }
        validateScope(userId, sessionId);
        loadSnapshot(sessionId).ifPresent(snapshot -> persist(sessionId, snapshot.without(requireText(key, "key"))));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        if (isAnonymousBootstrapSlot(userId)) {
            return Set.of();
        }
        if (!LOCAL_OPERATOR.equals(userId)) {
            throw new IllegalArgumentException("AgentScope 状态只允许 local-operator 访问");
        }
        return checkpointRepository.findRunIds();
    }

    /**
     * 加载指定 Run 的 AgentState；缺失、篡改、格式不兼容和归属不匹配均抛异常。
     */
    public AgentState loadVerifiedAgentState(String runId) {
        requireText(runId, "runId");
        StateSnapshot snapshot = loadSnapshot(runId)
                .orElseThrow(() -> new IllegalStateException("Agent 检查点不存在: " + runId));
        String agentStateJson = snapshot.stateJsonByKey().get(AGENT_STATE_KEY);
        if (agentStateJson == null || agentStateJson.isBlank()) {
            throw new IllegalStateException("Agent 检查点缺少 AgentScope 状态: " + runId);
        }
        AgentState agentState = decode(agentStateJson, AgentState.class);
        validateAgentStateIdentity(agentState, runId);
        return agentState;
    }

    private StateSnapshot loadSnapshotOrEmpty(String runId) {
        return loadSnapshot(runId).orElseGet(StateSnapshot::empty);
    }

    private Optional<StateSnapshot> loadSnapshot(String runId) {
        return checkpointRepository.findCheckpointByRunId(runId)
                .map(checkpoint -> decodeSnapshot(runId, checkpoint));
    }

    private StateSnapshot decodeSnapshot(String runId, AgentCheckpointEntity checkpoint) {
        verifyCheckpointIntegrity(checkpoint);
        try {
            envelopeCrypto.decrypt(checkpoint.getAuthorizationStateCiphertext(), authorizationAad(runId));
            envelopeCrypto.decrypt(checkpoint.getFinalizationContextCiphertext(), finalizationAad(runId));
            String json = envelopeCrypto.decrypt(checkpoint.getAgentscopeStateCiphertext(), agentscopeAad(runId));
            StateSnapshot snapshot = JsonUtils.getJsonCodec().fromJson(json, StateSnapshot.class);
            if (snapshot == null || snapshot.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException("Agent 检查点状态版本不兼容");
            }
            return snapshot;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Agent 检查点状态无法加载", e);
        }
    }

    private void persist(String runId, StateSnapshot snapshot) {
        String stateCiphertext = envelopeCrypto.encrypt(JsonUtils.getJsonCodec().toJson(snapshot), agentscopeAad(runId));
        AgentCheckpointEntity current = checkpointRepository.findCheckpointByRunId(runId).orElse(null);
        AgentCheckpointEntity checkpoint;
        if (current == null) {
            String authorizationCiphertext = envelopeCrypto.encrypt("{}", authorizationAad(runId));
            String finalizationCiphertext = envelopeCrypto.encrypt("{}", finalizationAad(runId));
            checkpoint = AgentCheckpointEntity.stable(runId, 1L, stateCiphertext,
                    authorizationCiphertext, finalizationCiphertext, FINALIZATION_CONTEXT_SCHEMA_VERSION,
                    SCHEMA_VERSION, checksum(stateCiphertext, authorizationCiphertext, finalizationCiphertext,
                            FINALIZATION_CONTEXT_SCHEMA_VERSION), Instant.now());
        } else {
            checkpoint = current;
            checkpoint.replace(current.getCheckpointSequence() + 1L, stateCiphertext,
                    current.getAuthorizationStateCiphertext(), current.getFinalizationContextCiphertext(),
                    current.getFinalizationContextSchemaVersion(), SCHEMA_VERSION,
                    checksum(stateCiphertext, current.getAuthorizationStateCiphertext(),
                            current.getFinalizationContextCiphertext(), current.getFinalizationContextSchemaVersion()),
                    Instant.now());
        }
        checkpointRepository.save(checkpoint);
    }

    private void verifyCheckpointIntegrity(AgentCheckpointEntity checkpoint) {
        String expected = checksum(checkpoint.getAgentscopeStateCiphertext(),
                checkpoint.getAuthorizationStateCiphertext(), checkpoint.getFinalizationContextCiphertext(),
                checkpoint.getFinalizationContextSchemaVersion());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                checkpoint.getChecksum().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Agent 检查点完整性校验失败");
        }
    }

    private String checksum(String stateCiphertext, String authorizationCiphertext,
                            String finalizationCiphertext, int finalizationSchemaVersion) {
        return envelopeCrypto.hmac(SCHEMA_VERSION + "|" + finalizationSchemaVersion + "|"
                + stateCiphertext + "|" + authorizationCiphertext + "|" + finalizationCiphertext);
    }

    private <T extends State> T decode(String json, Class<T> type) {
        try {
            T state = type == AgentState.class
                    ? type.cast(AgentState.fromJsonString(json))
                    : JsonUtils.getJsonCodec().fromJson(json, type);
            if (state == null) {
                throw new IllegalStateException("AgentScope 状态反序列化为空");
            }
            return state;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AgentScope 状态无法反序列化", e);
        }
    }

    private void validateScope(String userId, String runId) {
        if (!LOCAL_OPERATOR.equals(userId)) {
            throw new IllegalArgumentException("AgentScope 状态只允许 local-operator 访问");
        }
        requireText(runId, "runId");
    }

    /**
     * AgentScope 在绑定请求 RuntimeContext 前会探测匿名默认槽位；该槽位只能获得空状态，
     * 不能读取或写入任何真实 Run 的检查点。
     */
    private static boolean isAnonymousBootstrapSlot(String userId) {
        return userId == null || userId.isBlank();
    }

    private void validateAgentStateIdentity(AgentState state, String runId) {
        if (!runId.equals(state.getSessionId()) || !LOCAL_OPERATOR.equals(state.getUserId())) {
            throw new IllegalArgumentException("AgentScope 状态归属与 Run 不一致");
        }
    }

    private static String agentscopeAad(String runId) {
        return "agent_checkpoint:" + runId + ":agentscope:v1";
    }

    private static String authorizationAad(String runId) {
        return "agent_checkpoint:" + runId + ":authorization:v1";
    }

    private static String finalizationAad(String runId) {
        return "agent_checkpoint:" + runId + ":finalization:v1";
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

    private record StateSnapshot(int schemaVersion, Map<String, String> stateJsonByKey,
                                 Map<String, List<String>> listJsonByKey) {

        private StateSnapshot {
            stateJsonByKey = Map.copyOf(stateJsonByKey == null ? Map.of() : stateJsonByKey);
            Map<String, List<String>> copiedLists = new LinkedHashMap<>();
            if (listJsonByKey != null) {
                listJsonByKey.forEach((key, values) -> copiedLists.put(key, List.copyOf(values)));
            }
            listJsonByKey = Map.copyOf(copiedLists);
        }

        static StateSnapshot empty() {
            return new StateSnapshot(SCHEMA_VERSION, Map.of(), Map.of());
        }

        StateSnapshot withState(String key, String json) {
            Map<String, String> states = new LinkedHashMap<>(stateJsonByKey);
            states.put(key, json);
            return new StateSnapshot(schemaVersion, states, listJsonByKey);
        }

        StateSnapshot withList(String key, List<String> jsonValues) {
            Map<String, List<String>> lists = new LinkedHashMap<>(listJsonByKey);
            lists.put(key, List.copyOf(jsonValues));
            return new StateSnapshot(schemaVersion, stateJsonByKey, lists);
        }

        StateSnapshot without(String key) {
            Map<String, String> states = new LinkedHashMap<>(stateJsonByKey);
            Map<String, List<String>> lists = new LinkedHashMap<>(listJsonByKey);
            states.remove(key);
            lists.remove(key);
            return new StateSnapshot(schemaVersion, states, lists);
        }
    }
}
