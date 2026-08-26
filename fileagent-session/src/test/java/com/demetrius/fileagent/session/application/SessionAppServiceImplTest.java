package com.demetrius.fileagent.session.application;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.session.domain.SessionEntity;
import com.demetrius.fileagent.session.domain.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionAppServiceImpl} 用例测试：创建、列表排序、消息与 404。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class SessionAppServiceImplTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionQueryPort sessionQueryPort;

    @InjectMocks
    private SessionAppServiceImpl sessionAppService;

    @Test
    void createSessionShouldSaveEntityWithTitle() {
        SessionEntity saved = session(1L, "项目讨论", LocalDateTime.of(2026, 8, 26, 10, 0));
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(saved);

        SessionDto dto = sessionAppService.createSession(new CreateSessionReq("项目讨论"));

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("项目讨论");
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.title()).isEqualTo("项目讨论");
        assertThat(dto.createdAt()).isEqualTo("2026-08-26T10:00");
    }

    @Test
    void createSessionShouldUseDefaultTitleWhenBlank() {
        SessionEntity saved = session(2L, "新会话", LocalDateTime.of(2026, 8, 26, 11, 0));
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(saved);

        sessionAppService.createSession(new CreateSessionReq("   "));

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("新会话");
    }

    @Test
    void listSessionsShouldKeepRepositoryOrder() {
        when(sessionRepository.findAll()).thenReturn(List.of(
                session(3L, "最近会话", LocalDateTime.of(2026, 8, 26, 12, 0)),
                session(1L, "较早会话", LocalDateTime.of(2026, 8, 20, 9, 0))
        ));

        List<SessionDto> result = sessionAppService.listSessions();

        assertThat(result).extracting(SessionDto::id, SessionDto::title)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3L, "最近会话"),
                        org.assertj.core.groups.Tuple.tuple(1L, "较早会话")
                );
    }

    @Test
    void listMessagesShouldReturnPortResult() {
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.listMessages(1L)).thenReturn(List.of(
                new MessageDto(10L, 1L, MessageType.USER, "你好", null, "2026-08-26T10:00"),
                new MessageDto(11L, 1L, MessageType.ASSISTANT, "你好，有什么可以帮你？", null, "2026-08-26T10:01")
        ));

        List<MessageDto> result = sessionAppService.listMessages(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(MessageType.USER);
        assertThat(result.get(1).role()).isEqualTo(MessageType.ASSISTANT);
    }

    @Test
    void listMessagesShouldThrowBiz404WhenSessionMissing() {
        when(sessionQueryPort.exists(99L)).thenReturn(false);

        assertThatThrownBy(() -> sessionAppService.listMessages(99L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
    }

    private SessionEntity session(Long id, String title, LocalDateTime createdAt) {
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
        return entity;
    }
}
