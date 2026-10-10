package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 Run 的有界内存事件通道：生成稳定事件 ID，并向 SSE 订阅者提供有限窗口内的回放。
 * <p>
 * 此处只保存传输事实，Run 的状态、步骤和最终回答仍以数据库记录为准；进程重启后由调用方
 * 返回当前 {@code run.status} 快照，而不是伪造已经丢失的增量文本。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@Slf4j
public class AgentRunEventHub {

    private static final String DELTA_EVENT_TYPE = "message.delta";

    private final Map<String, RunChannel> channels = new ConcurrentHashMap<>();
    private final int maxReplayEvents;
    private final int maxReplayBytes;

    public AgentRunEventHub(AgentProperties properties) {
        this.maxReplayEvents = properties.getReplayMaxEvents();
        this.maxReplayBytes = properties.getReplayMaxBytes();
    }

    /**
     * 为原始运行事件补全传输元数据，并广播给该 Run 的当前订阅者。
     *
     * @param event 不含 eventId 的受控运行事件
     * @param runVersion 事件对应的持久化 Run 版本
     * @return 已包含 eventId 与 runVersion 的实际发布事件
     */
    public AgentRunEvent publish(AgentRunEvent event, long runVersion) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            throw new IllegalArgumentException("事件 runId 不能为空");
        }
        if (runVersion < 0) {
            throw new IllegalArgumentException("runVersion 不能小于 0");
        }
        AgentRunEvent published = event.withMetadata(UUID.randomUUID().toString(), runVersion);
        channels.computeIfAbsent(event.runId(), runId -> new RunChannel(runId, maxReplayEvents, maxReplayBytes))
                .publish(published);
        return published;
    }

    /**
     * 订阅指定 Run。有效游标只回放其后的内存事件；缺失或失效游标仅发送当前状态快照。
     *
     * @param runId 订阅的 Run 标识
     * @param lastEventId 浏览器已成功处理的最后一个事件 ID，可为空
     * @param currentStatus 从数据库读取的当前 Run 状态
     * @param currentRunVersion 从数据库读取的当前 Run 版本
     * @return 回放后持续接收新事件的 Flux
     */
    public Flux<AgentRunEvent> subscribe(String runId, String lastEventId,
                                         AgentRunStatus currentStatus, long currentRunVersion) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (currentStatus == null) {
            throw new IllegalArgumentException("currentStatus 不能为空");
        }
        if (currentRunVersion < 0) {
            throw new IllegalArgumentException("currentRunVersion 不能小于 0");
        }
        RunChannel channel = channels.computeIfAbsent(runId,
                id -> new RunChannel(id, maxReplayEvents, maxReplayBytes));
        return channel.subscribe(runId, lastEventId, currentStatus, currentRunVersion);
    }

    private static boolean isTerminal(AgentRunEvent event) {
        return event.status() == AgentRunStatus.SUCCEEDED
                || event.status() == AgentRunStatus.FAILED
                || event.status() == AgentRunStatus.CANCELLED
                || event.status() == AgentRunStatus.TIMED_OUT;
    }

    private static int estimateBytes(AgentRunEvent event) {
        int total = 128;
        total += utf8Length(event.type());
        total += utf8Length(event.runId());
        total += utf8Length(event.summary());
        total += utf8Length(event.tool());
        total += utf8Length(event.content());
        total += utf8Length(event.code());
        total += utf8Length(event.message());
        total += utf8Length(event.traceId());
        total += utf8Length(event.eventId());
        if (event.files() != null) {
            for (String file : event.files()) {
                total += utf8Length(file);
            }
        }
        return total;
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class RunChannel {

        private final String runId;
        private final Object monitor = new Object();
        private final Deque<RetainedEvent> replayEvents = new ArrayDeque<>();
        private final Sinks.Many<AgentRunEvent> sink = Sinks.many().multicast().directBestEffort();
        private final int maxReplayEvents;
        private final int maxReplayBytes;
        private int retainedBytes;
        private boolean terminal;
        private boolean liveDeliveryDegraded;

        private RunChannel(String runId, int maxReplayEvents, int maxReplayBytes) {
            this.runId = runId;
            this.maxReplayEvents = maxReplayEvents;
            this.maxReplayBytes = maxReplayBytes;
        }

        void publish(AgentRunEvent event) {
            synchronized (monitor) {
                if (terminal) {
                    throw new IllegalStateException("Agent Run 已终态，不能继续发布事件");
                }
                retain(event);
                Sinks.EmitResult result = sink.tryEmitNext(event);
                if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                    reportLiveDeliveryDegraded(result);
                } else if (result != Sinks.EmitResult.OK && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    throw new IllegalStateException("Agent 事件发布失败: " + result);
                }
                if (isTerminal(event)) {
                    terminal = true;
                    sink.tryEmitComplete();
                }
            }
        }

        private void reportLiveDeliveryDegraded(Sinks.EmitResult result) {
            if (!liveDeliveryDegraded) {
                liveDeliveryDegraded = true;
                log.warn("Agent SSE 实时事件投递降级，客户端可通过重连回放或读取持久化结果恢复: runId={}, result={}",
                        runId, result);
            }
        }

        Flux<AgentRunEvent> subscribe(String runId, String lastEventId,
                                      AgentRunStatus currentStatus, long currentRunVersion) {
            return Flux.defer(() -> {
                List<AgentRunEvent> initialEvents;
                synchronized (monitor) {
                    initialEvents = replayAfter(lastEventId);
                    boolean initialSubscription = lastEventId == null || lastEventId.isBlank();
                    if (initialEvents == null || (initialSubscription && initialEvents.isEmpty())) {
                        initialEvents = List.of(AgentRunEvent.status(runId, currentStatus, null)
                                .withMetadata(UUID.randomUUID().toString(), currentRunVersion));
                    }
                }
                return Flux.mergeSequential(Flux.fromIterable(initialEvents), liveEvents());
            });
        }

        private Flux<AgentRunEvent> liveEvents() {
            return sink.asFlux()
                    // 每个 SSE 连接单独缓冲，避免 MVC 逐帧请求时直接丢失 Agent 的突发事件。
                    .onBackpressureBuffer(maxReplayEvents)
                    .doOnError(error -> log.warn("Agent SSE 客户端事件缓冲已满，将等待浏览器按游标重连: runId={}", runId));
        }

        private List<AgentRunEvent> replayAfter(String lastEventId) {
            if (lastEventId == null || lastEventId.isBlank()) {
                return replayEvents.stream().map(RetainedEvent::event).toList();
            }
            boolean found = false;
            List<AgentRunEvent> replay = new ArrayList<>();
            for (RetainedEvent retained : replayEvents) {
                if (found) {
                    replay.add(retained.event());
                } else if (lastEventId.equals(retained.event().eventId())) {
                    found = true;
                }
            }
            return found ? List.copyOf(replay) : null;
        }

        private void retain(AgentRunEvent event) {
            int eventBytes = estimateBytes(event);
            if (DELTA_EVENT_TYPE.equals(event.type()) && eventBytes > maxReplayBytes) {
                return;
            }
            while (wouldOverflow(eventBytes)) {
                RetainedEvent evicted = removeOldestDelta();
                if (evicted == null && !DELTA_EVENT_TYPE.equals(event.type())) {
                    evicted = removeOldestNonTerminal();
                }
                if (evicted == null) {
                    return;
                }
            }
            replayEvents.addLast(new RetainedEvent(event, eventBytes));
            retainedBytes += eventBytes;
        }

        private boolean wouldOverflow(int eventBytes) {
            return replayEvents.size() >= maxReplayEvents || retainedBytes + eventBytes > maxReplayBytes;
        }

        private RetainedEvent removeOldestDelta() {
            return removeFirstMatching(event -> DELTA_EVENT_TYPE.equals(event.type()));
        }

        private RetainedEvent removeOldestNonTerminal() {
            return removeFirstMatching(event -> !isTerminal(event));
        }

        private RetainedEvent removeFirstMatching(java.util.function.Predicate<AgentRunEvent> predicate) {
            java.util.Iterator<RetainedEvent> iterator = replayEvents.iterator();
            while (iterator.hasNext()) {
                RetainedEvent candidate = iterator.next();
                if (predicate.test(candidate.event())) {
                    iterator.remove();
                    retainedBytes -= candidate.bytes();
                    return candidate;
                }
            }
            return null;
        }
    }

    private record RetainedEvent(AgentRunEvent event, int bytes) {
    }
}
