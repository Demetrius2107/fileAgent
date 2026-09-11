package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.chat.application.ChatAppService;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ChatAppService chatAppService;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    private ChatController chatController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn(TRACE_ID);
        chatController = new ChatController(chatAppService, tracer, new ObjectMapper());
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
                .andExpect(header().string("X-Trace-Id", TRACE_ID))
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:message").contains("event:sources").contains("event:done");
        assertThat(body).doesNotContain("\"traceId\":\"" + TRACE_ID + "\"");
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
                .andExpect(header().string("X-Trace-Id", TRACE_ID))
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:error")
                .contains("MODEL_STREAM_FAILED")
                .contains("\"traceId\":\"" + TRACE_ID + "\"");
    }

    @Test
    void chatShouldReturnNotFoundSseWhenSessionDoesNotExistBeforeStreamStarts() throws Exception {
        when(chatAppService.chat(eq(999999L), any(ChatReq.class)))
                .thenThrow(new BizException(404, "会话不存在"));

        String body = mockMvc.perform(post("/api/sessions/999999/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"prompt\":\"问题\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", TRACE_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:error")
                .contains("\"code\":\"404\"")
                .contains("会话不存在")
                .contains("\"traceId\":\"" + TRACE_ID + "\"");
    }
}
