package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentRunEventHub} 的游标回放、终态保留和未知游标收敛测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
class AgentRunEventHubTest {

    @Test
    void shouldReplayOnlyEventsAfterLastEventIdWithStableEventId() {
        AgentRunEventHub eventHub = eventHub();
        AgentRunEvent started = eventHub.publish(AgentRunEvent.runStarted("run-1", "trace-1"), 1L);
        AgentRunEvent delta = eventHub.publish(AgentRunEvent.delta("run-1", "第一段回答"), 2L);

        List<AgentRunEvent> firstReplay = eventHub.subscribe("run-1", started.eventId(),
                        AgentRunStatus.RUNNING, 2L)
                .take(1)
                .collectList()
                .block();
        List<AgentRunEvent> repeatedReplay = eventHub.subscribe("run-1", started.eventId(),
                        AgentRunStatus.RUNNING, 2L)
                .take(1)
                .collectList()
                .block();

        assertThat(firstReplay).containsExactly(delta);
        assertThat(repeatedReplay).containsExactly(delta);
        assertThat(delta.eventId()).isNotBlank();
        assertThat(delta.runVersion()).isEqualTo(2L);
    }

    @Test
    void shouldKeepTerminalEventWhenDeltaReplayBufferOverflows() {
        AgentRunEventHub eventHub = eventHub();
        AgentRunEvent cursor = eventHub.publish(AgentRunEvent.status("run-2", AgentRunStatus.RUNNING, null), 1L);
        for (int index = 0; index < 101; index++) {
            eventHub.publish(AgentRunEvent.delta("run-2", "delta-" + index), index + 2L);
        }
        AgentRunEvent completed = eventHub.publish(AgentRunEvent.completed("run-2", 100L), 103L);

        List<AgentRunEvent> replay = eventHub.subscribe("run-2", cursor.eventId(),
                        AgentRunStatus.SUCCEEDED, 103L)
                .collectList()
                .block();

        assertThat(replay).hasSizeLessThanOrEqualTo(99);
        assertThat(replay).contains(completed);
        assertThat(replay).allMatch(event -> event.eventId() != null && !event.eventId().isBlank());
    }

    @Test
    void shouldReturnCurrentStatusOnlyWhenLastEventIdIsUnknown() {
        AgentRunEventHub eventHub = eventHub();
        eventHub.publish(AgentRunEvent.delta("run-3", "不会重放的增量"), 2L);

        List<AgentRunEvent> events = eventHub.subscribe("run-3", "expired-event-id",
                        AgentRunStatus.WAITING_APPROVAL, 7L)
                .take(1)
                .collectList()
                .block();

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("run.status");
            assertThat(event.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(event.runVersion()).isEqualTo(7L);
            assertThat(event.eventId()).isNotBlank();
        });
    }

    @Test
    void shouldExcludeOversizedDeltaFromReplayWithoutLosingTerminalEvent() {
        AgentRunEventHub eventHub = eventHub();
        AgentRunEvent cursor = eventHub.publish(AgentRunEvent.status("run-4", AgentRunStatus.RUNNING, null), 1L);
        eventHub.publish(AgentRunEvent.delta("run-4", "x".repeat(256 * 1024)), 2L);
        AgentRunEvent completed = eventHub.publish(AgentRunEvent.completed("run-4", 400L), 3L);

        List<AgentRunEvent> replay = eventHub.subscribe("run-4", cursor.eventId(),
                        AgentRunStatus.SUCCEEDED, 3L)
                .collectList()
                .block();

        assertThat(replay).containsExactly(completed);
    }

    @Test
    void shouldNotFailRunWhenSlowSubscriberCannotAcceptLiveEvent() {
        AgentRunEventHub eventHub = eventHub();
        AgentRunEvent cursor = eventHub.publish(AgentRunEvent.status("run-5", AgentRunStatus.RUNNING, null), 1L);
        List<AgentRunEvent> received = new ArrayList<>();
        BaseSubscriber<AgentRunEvent> slowSubscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // 不请求数据，模拟浏览器连接仍在但暂时无法消费 SSE 的背压状态。
            }

            @Override
            protected void hookOnNext(AgentRunEvent event) {
                received.add(event);
            }
        };
        eventHub.subscribe("run-5", cursor.eventId(), AgentRunStatus.RUNNING, 1L).subscribe(slowSubscriber);
        AgentRunEvent delta = AgentRunEvent.delta("run-5", "仍应保留的回答片段");

        try {
            AgentRunEvent publishedDelta = eventHub.publish(delta, 2L);

            assertThat(received).isEmpty();
            slowSubscriber.request(1);
            assertThat(received).containsExactly(publishedDelta);

            List<AgentRunEvent> replay = eventHub.subscribe("run-5", cursor.eventId(),
                            AgentRunStatus.RUNNING, 2L)
                    .take(1)
                    .collectList()
                    .block();
            assertThat(replay).containsExactly(publishedDelta);
        } finally {
            slowSubscriber.cancel();
        }
    }

    @Test
    void shouldCaptureLiveEventWhileInitialReplayIsStillBeingDelivered() {
        AgentRunEventHub eventHub = eventHub();
        AgentRunEvent started = eventHub.publish(AgentRunEvent.runStarted("run-6", "trace-6"), 1L);
        AgentRunEvent replayedDelta = eventHub.publish(AgentRunEvent.delta("run-6", "已保留事件"), 2L);
        List<AgentRunEvent> received = new ArrayList<>();
        BaseSubscriber<AgentRunEvent> slowSubscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(AgentRunEvent event) {
                received.add(event);
            }
        };

        try {
            eventHub.subscribe("run-6", null, AgentRunStatus.RUNNING, 2L).subscribe(slowSubscriber);
            AgentRunEvent liveDelta = eventHub.publish(AgentRunEvent.delta("run-6", "订阅期间事件"), 3L);
            slowSubscriber.request(10);

            assertThat(received).containsExactly(started, replayedDelta, liveDelta);
        } finally {
            slowSubscriber.cancel();
        }
    }

    private AgentRunEventHub eventHub() {
        return new AgentRunEventHub(new AgentProperties());
    }
}
