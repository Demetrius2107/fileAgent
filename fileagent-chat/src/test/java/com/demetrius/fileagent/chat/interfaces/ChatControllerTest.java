package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.chat.application.ChatAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ChatController} SSE 接口测试：Content-Type 与事件顺序。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatAppService chatAppService;

    @InjectMocks
    private ChatController chatController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(chatController).build();
    }

    @Test
    void chatShouldStreamSseEventsInMessageSourcesDoneOrder() throws Exception {
        when(chatAppService.chat(eq(1L), any(ChatReq.class))).thenReturn(Flux.just(
                ChatStreamEvent.message("回答"),
                ChatStreamEvent.sources("KNOWLEDGE", List.of("员工手册.pdf")),
                ChatStreamEvent.done(101L)));

        MvcResult result = mockMvc.perform(post("/api/sessions/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:message").contains("event:sources").contains("event:done");
        assertThat(body.indexOf("event:message")).isLessThan(body.indexOf("event:sources"));
        assertThat(body.indexOf("event:sources")).isLessThan(body.indexOf("event:done"));
        assertThat(body).contains("回答").contains("员工手册.pdf");
    }

    @Test
    void chatShouldDeliverErrorEventWithSuccessfulStream() throws Exception {
        when(chatAppService.chat(eq(1L), any(ChatReq.class))).thenReturn(Flux.just(
                ChatStreamEvent.error("MODEL_STREAM_FAILED", "模型调用失败，请稍后重试")));

        MvcResult result = mockMvc.perform(post("/api/sessions/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:error").contains("MODEL_STREAM_FAILED");
    }
}
