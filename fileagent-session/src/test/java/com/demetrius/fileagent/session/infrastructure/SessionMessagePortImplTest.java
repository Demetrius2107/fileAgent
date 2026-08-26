package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.session.domain.MessageEntity;
import com.demetrius.fileagent.session.domain.SessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionMessagePortImpl} 端口测试：消息落库、会话时间戳与 404。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class SessionMessagePortImplTest {

    @Mock
    private SessionJpaRepository sessionJpaRepository;

    @Mock
    private MessageJpaRepository messageJpaRepository;

    @InjectMocks
    private SessionMessagePortImpl sessionMessagePort;

    @Test
    void appendShouldThrowBiz404WhenSessionMissing() {
        when(sessionJpaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionMessagePort.append(99L, MessageType.USER, "你好"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
    }

    @Test
    void appendShouldSaveMessageAndUpdateSessionTimestamp() {
        SessionEntity session = new SessionEntity();
        session.setId(1L);
        session.setTitle("会话一");
        LocalDateTime stale = LocalDateTime.of(2026, 8, 20, 9, 0);
        session.setCreatedAt(stale);
        session.setUpdatedAt(stale);
        when(sessionJpaRepository.findById(1L)).thenReturn(Optional.of(session));

        MessageEntity saved = new MessageEntity();
        saved.setId(77L);
        saved.setSession(session);
        saved.setRole(MessageType.USER);
        saved.setContent("你好");
        when(messageJpaRepository.save(any(MessageEntity.class))).thenReturn(saved);
        when(sessionJpaRepository.save(any(SessionEntity.class))).thenReturn(session);

        Long messageId = sessionMessagePort.append(1L, MessageType.USER, "你好");

        assertThat(messageId).isEqualTo(77L);
        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageJpaRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSession()).isSameAs(session);
        assertThat(messageCaptor.getValue().getRole()).isEqualTo(MessageType.USER);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("你好");

        ArgumentCaptor<SessionEntity> sessionCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionJpaRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getUpdatedAt()).isAfter(stale);
    }
}
