package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.session.domain.MessageEntity;
import com.demetrius.fileagent.session.domain.SessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话滚动摘要的 H2 持久化和检查点 CAS 验证。
 *
 * @author raosaijie
 */
@DataJpaTest
@Import({SessionQueryPortImpl.class, SessionMessagePortImpl.class})
class SessionSummaryJpaTest {

    @Autowired
    private SessionJpaRepository sessionJpaRepository;

    @Autowired
    private MessageJpaRepository messageJpaRepository;

    @Autowired
    private SessionQueryPort sessionQueryPort;

    @Autowired
    private SessionMessagePort sessionMessagePort;

    @Test
    void newSessionShouldExposeEmptySummaryAtVersionZero() {
        SessionEntity session = sessionJpaRepository.save(new SessionEntity());

        SessionQueryPort.SessionSummary summary = sessionQueryPort.getSummary(session.getId());

        assertThat(summary.content()).isNull();
        assertThat(summary.throughMessageId()).isNull();
        assertThat(summary.version()).isZero();
    }

    @Test
    void summaryUpdateShouldRequireExpectedVersionAndMoveCheckpointForward() {
        SessionEntity session = sessionJpaRepository.save(new SessionEntity());
        Long firstMessageId = appendMessage(session, "早期约束");
        Long secondMessageId = appendMessage(session, "后续决定");

        assertThat(sessionMessagePort.updateSummary(
                session.getId(), 0L, "{\"confirmedFacts\":[\"早期约束\"]}", firstMessageId, "hash-1"))
                .isTrue();
        assertThat(sessionMessagePort.updateSummary(
                session.getId(), 0L, "{\"confirmedFacts\":[\"覆盖失败\"]}", secondMessageId, "hash-stale"))
                .isFalse();
        assertThat(sessionMessagePort.updateSummary(
                session.getId(), 1L, "{\"decisions\":[\"后续决定\"]}", secondMessageId, "hash-2"))
                .isTrue();
        assertThat(sessionMessagePort.updateSummary(
                session.getId(), 1L, "{\"decisions\":[\"检查点回退\"]}", firstMessageId, "hash-backward"))
                .isFalse();

        SessionQueryPort.SessionSummary summary = sessionQueryPort.getSummary(session.getId());
        assertThat(summary.content()).isEqualTo("{\"decisions\":[\"后续决定\"]}");
        assertThat(summary.throughMessageId()).isEqualTo(secondMessageId);
        assertThat(summary.version()).isEqualTo(2L);
        assertThat(summary.sourceHash()).isEqualTo("hash-2");
    }

    @Test
    void listMessagesAfterShouldReturnOnlyMessagesAfterCheckpointInTimeOrder() {
        SessionEntity session = sessionJpaRepository.save(new SessionEntity());
        Long firstMessageId = appendMessage(session, "第一条");
        Long secondMessageId = appendMessage(session, "第二条");
        Long thirdMessageId = appendMessage(session, "第三条");

        assertThat(sessionQueryPort.listMessagesAfter(session.getId(), firstMessageId))
                .extracting(message -> message.id())
                .containsExactly(secondMessageId, thirdMessageId);
    }

    private Long appendMessage(SessionEntity session, String content) {
        MessageEntity message = new MessageEntity();
        message.setSession(session);
        message.setRole(MessageType.USER);
        message.setContent(content);
        return messageJpaRepository.save(message).getId();
    }
}
