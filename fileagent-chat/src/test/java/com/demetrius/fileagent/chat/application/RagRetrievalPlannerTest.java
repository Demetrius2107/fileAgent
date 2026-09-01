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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrievalPlanner} 测试。
 *
 * @author raosaijie
 */
class RagRetrievalPlannerTest {

    @Test
    void planShouldRewriteFollowUpIntoStandaloneQuery() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("""
                {"needRetrieval":true,"standaloneQuery":"2026年研发部全部年度目标","answerMode":"LIST_ALL"}
                """);
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "2026年研发部的年度目标有哪些", null,
                        "2026-08-28T10:00"),
                new MessageDto(2L, 1L, MessageType.ASSISTANT, "目前检索到两个目标", null,
                        "2026-08-28T10:01"));

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(history, "请完整列出来");

        assertThat(plan.needRetrieval()).isTrue();
        assertThat(plan.standaloneQuery()).isEqualTo("2026年研发部全部年度目标");
        assertThat(plan.answerMode()).isEqualTo("LIST_ALL");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getContents())
                .contains("2026年研发部的年度目标有哪些")
                .contains("请完整列出来");
    }

    @Test
    void planShouldReturnNoRetrievalForGeneralConversation() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("""
                {"needRetrieval":false,"standaloneQuery":"","answerMode":"SINGLE"}
                """);
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(List.of(), "你好");

        assertThat(plan.needRetrieval()).isFalse();
        assertThat(plan.standaloneQuery()).isEmpty();
        assertThat(plan.answerMode()).isEqualTo("SINGLE");
    }

    @Test
    void planShouldFallBackToOriginalQuestionWhenModelOutputIsInvalid() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("不是 JSON");
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(List.of(), "年假怎么申请");

        assertThat(plan.needRetrieval()).isTrue();
        assertThat(plan.standaloneQuery()).isEqualTo("年假怎么申请");
        assertThat(plan.answerMode()).isEqualTo("SINGLE");
    }

    @Test
    void planShouldFallBackToListAllForEnumerationQuestion() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型不可用"));
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(List.of(), "制度中有哪些审批步骤");

        assertThat(plan.standaloneQuery()).isEqualTo("制度中有哪些审批步骤");
        assertThat(plan.answerMode()).isEqualTo("LIST_ALL");
    }
}
