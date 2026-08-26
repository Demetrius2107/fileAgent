package com.demetrius.fileagent.session.interfaces;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.exception.GlobalExceptionHandler;
import com.demetrius.fileagent.session.application.SessionAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SessionController} 接口测试：ApiResult 包装与 404 映射。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionAppService sessionAppService;

    @InjectMocks
    private SessionController sessionController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sessionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createShouldReturnApiResultWithSession() throws Exception {
        when(sessionAppService.createSession(any(CreateSessionReq.class)))
                .thenReturn(new SessionDto(1L, "新会话", "2026-08-26T10:00"));

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新会话\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("新会话"));
    }

    @Test
    void listShouldReturnApiResultWithSessions() throws Exception {
        when(sessionAppService.listSessions()).thenReturn(List.of(
                new SessionDto(2L, "第二个", "2026-08-26T11:00"),
                new SessionDto(1L, "第一个", "2026-08-25T11:00")
        ));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(2));
    }

    @Test
    void messagesShouldReturnApiResultWithMessages() throws Exception {
        when(sessionAppService.listMessages(1L)).thenReturn(List.of(
                new MessageDto(10L, 1L, MessageType.USER, "你好", null, "2026-08-26T10:00")
        ));

        mockMvc.perform(get("/api/sessions/1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].content").value("你好"))
                .andExpect(jsonPath("$.data[0].role").value("USER"));
    }

    @Test
    void messagesShouldReturnHttp404WhenSessionMissing() throws Exception {
        when(sessionAppService.listMessages(99L))
                .thenThrow(new BizException(404, "会话不存在"));

        mockMvc.perform(get("/api/sessions/99/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void createShouldReturnHttp400WhenBiz400() throws Exception {
        when(sessionAppService.createSession(any(CreateSessionReq.class)))
                .thenThrow(new BizException(400, "标题过长"));

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
