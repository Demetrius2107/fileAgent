package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagQueryRewriter} 测试。
 *
 * @author raosaijie
 */
class RagQueryRewriterTest {

    @Test
    void rewriteShouldReturnOriginalQuestionWithoutPreviousUserMessage() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        RagQueryRewriter rewriter = new RagQueryRewriter(chatClient);

        String result = rewriter.rewrite(List.of(), "2026年饶赛杰的目标有哪些");

        assertThat(result).isEqualTo("2026年饶赛杰的目标有哪些");
        verify(chatClient, never()).call(any(Prompt.class));
    }

    @Test
    void rewriteShouldTurnFollowUpIntoStandaloneQuery() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("""
                {"standaloneQuery":"2026年饶赛杰的OKR目标有哪些"}
                """);
        RagQueryRewriter rewriter = new RagQueryRewriter(chatClient);
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "2025年饶赛杰的OKR目标有哪些", null,
                        "2026-08-28T10:00"),
                new MessageDto(2L, 1L, MessageType.ASSISTANT, "目前检索到五个目标", null,
                        "2026-08-28T10:01"));

        String result = rewriter.rewrite(history, "我问的是2026年");

        assertThat(result).isEqualTo("2026年饶赛杰的OKR目标有哪些");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getContents())
                .contains("2025年饶赛杰的OKR目标有哪些")
                .contains("我问的是2026年");
    }

    @Test
    void rewriteShouldFallBackToOriginalQuestionWhenModelOutputIsInvalid() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("不是 JSON");
        RagQueryRewriter rewriter = new RagQueryRewriter(chatClient);
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "上一轮问题", null,
                        "2026-08-28T10:00"));

        String result = rewriter.rewrite(history, "年假怎么申请");

        assertThat(result).isEqualTo("年假怎么申请");
    }
}
