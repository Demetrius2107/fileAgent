package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
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
 * {@link RagRetrievalPlanner} 测试：追问改写、完整性补检索与模型异常降级。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-28
 */
class RagRetrievalPlannerTest {

    @Test
    void planShouldRewriteFollowUpIntoStandaloneQuery() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("""
                {"needRetrieval":true,"standaloneQuery":"饶赛杰2025年的全部Objective","answerMode":"LIST_ALL"}
                """);
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "2025年饶赛杰的目标都有哪些", null, "2026-08-28T10:00"),
                new MessageDto(2L, 1L, MessageType.ASSISTANT, "Objective 4", null, "2026-08-28T10:01"));

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(history, "肯定有前三个啊");

        assertThat(plan.needRetrieval()).isTrue();
        assertThat(plan.standaloneQuery()).isEqualTo("饶赛杰2025年的全部Objective");
        assertThat(plan.answerMode()).isEqualTo("LIST_ALL");
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getContents())
                .contains("2025年饶赛杰的目标都有哪些")
                .contains("肯定有前三个啊");
    }

    @Test
    void retryQueryShouldTargetMissingItemsWhenFirstSearchIsIncomplete() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("""
                {"retry":true,"retryQuery":"饶赛杰2025年 Objective 1 Objective 2 Objective 3"}
                """);
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);
        RagRetrievalPlanner.RetrievalPlan plan =
                new RagRetrievalPlanner.RetrievalPlan(true, "饶赛杰2025年的全部Objective", "LIST_ALL");
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                new KnowledgeSearchPort.KnowledgeHit("Objective 4：提升团队成员的技能和职业发展", "2025OKR-饶赛杰.xlsx"));

        assertThat(planner.retryQuery("2025年饶赛杰的目标都有哪些", plan, hits))
                .contains("饶赛杰2025年 Objective 1 Objective 2 Objective 3");
    }

    @Test
    void planShouldFallBackToOriginalQuestionWhenModelOutputIsInvalid() {
        StreamingChatClient chatClient = mock(StreamingChatClient.class);
        when(chatClient.call(any(Prompt.class))).thenReturn("不是 JSON");
        RagRetrievalPlanner planner = new RagRetrievalPlanner(chatClient);

        RagRetrievalPlanner.RetrievalPlan plan = planner.plan(List.of(), "年假怎么申请");

        assertThat(plan.needRetrieval()).isTrue();
        assertThat(plan.standaloneQuery()).isEqualTo("年假怎么申请");
    }
}
