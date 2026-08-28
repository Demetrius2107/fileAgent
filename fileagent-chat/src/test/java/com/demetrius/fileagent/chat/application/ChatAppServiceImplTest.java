package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAppServiceImpl} 编排测试：历史截取、事件顺序、来源去重与异常路径。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class ChatAppServiceImplTest {

    private static final String NO_KNOWLEDGE_NOTICE = "未检索到相关知识库内容，以下回答来自模型通用知识。";

    @Mock
    private SessionQueryPort sessionQueryPort;

    @Mock
    private SessionMessagePort sessionMessagePort;

    @Mock
    private KnowledgeSearchPort knowledgeSearchPort;

    @Mock
    private RagPromptBuilder ragPromptBuilder;

    @Mock
    private RagRetrievalPlanner ragRetrievalPlanner;

    @Mock
    private StreamingChatClient streamingChatClient;

    @InjectMocks
    private ChatAppServiceImpl chatAppService;

    @Captor
    private ArgumentCaptor<List<MessageDto>> historyCaptor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatAppService, "chatHistoryLimit", 10);
        lenient().when(ragRetrievalPlanner.plan(anyList(), anyString()))
                .thenAnswer(invocation -> new RagRetrievalPlanner.RetrievalPlan(
                        true, invocation.getArgument(1), "SINGLE"));
        lenient().when(ragRetrievalPlanner.retryQuery(anyString(), any(), anyList()))
                .thenReturn(Optional.empty());
    }

    @Test
    void chatShouldStreamMessagesThenSourcesThenDoneOnKnowledgeHit() {
        stubSessionBasics(1L, twelveMessages());
        AtomicBoolean userSaved = new AtomicBoolean(false);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), eq("问题")))
                .thenAnswer(invocation -> {
                    userSaved.set(true);
                    return 100L;
                });
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString()))
                .thenReturn(101L);
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                new KnowledgeSearchPort.KnowledgeHit("片段A", "a.pdf"),
                new KnowledgeSearchPort.KnowledgeHit("片段B", "b.pdf"),
                new KnowledgeSearchPort.KnowledgeHit("片段C", "a.pdf"));
        when(knowledgeSearchPort.search("问题")).thenReturn(hits);
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), eq(hits), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("回答", "内容")
                .doOnSubscribe(s -> {
                    if (!userSaved.get()) {
                        throw new IllegalStateException("USER 消息未在模型订阅前保存");
                    }
                }));

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNext(ChatStreamEvent.message("回答"))
                .expectNext(ChatStreamEvent.message("内容"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.answerSource().equals("KNOWLEDGE")
                        && event.files().equals(List.of("a.pdf", "b.pdf")))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(ragPromptBuilder).build(historyCaptor.capture(), eq(hits), eq("问题"));
        assertThat(historyCaptor.getValue()).hasSize(10);
        assertThat(historyCaptor.getValue().get(0).id()).isEqualTo(3L);

        InOrder inOrder = inOrder(sessionQueryPort, sessionMessagePort);
        inOrder.verify(sessionQueryPort).listMessages(1L);
        inOrder.verify(sessionMessagePort).append(eq(1L), eq(MessageType.USER), eq("问题"));

        verify(sessionMessagePort, times(1)).append(eq(1L), eq(MessageType.USER), eq("问题"));
        verify(sessionMessagePort, times(1)).append(eq(1L), eq(MessageType.ASSISTANT), eq("回答内容"));
    }

    @Test
    void chatShouldPrependNoticeAndSaveItIntoAssistantOnKnowledgeMiss() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        when(knowledgeSearchPort.search("闲聊")).thenReturn(List.of());
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("闲聊"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("通用", "回答"));

        StepVerifier.create(chatAppService.chat(1L, "闲聊"))
                .expectNext(ChatStreamEvent.message(NO_KNOWLEDGE_NOTICE))
                .expectNext(ChatStreamEvent.message("通用"))
                .expectNext(ChatStreamEvent.message("回答"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.answerSource().equals("MODEL_GENERAL")
                        && event.files().isEmpty())
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(sessionMessagePort, times(1)).append(eq(1L), eq(MessageType.ASSISTANT),
                eq(NO_KNOWLEDGE_NOTICE + "\n通用回答"));
    }

    @Test
    void chatShouldRunOneTargetedRetryAndMergeHitsForIncompleteListQuestion() {
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "2025年饶赛杰的目标都有哪些", null, "2026-08-28T10:00"),
                new MessageDto(2L, 1L, MessageType.ASSISTANT, "Objective 4", null, "2026-08-28T10:01"));
        stubSessionBasics(1L, history);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        RagRetrievalPlanner.RetrievalPlan plan = new RagRetrievalPlanner.RetrievalPlan(
                true, "饶赛杰2025年的全部Objective", "LIST_ALL");
        when(ragRetrievalPlanner.plan(history, "肯定有前三个啊")).thenReturn(plan);
        List<KnowledgeSearchPort.KnowledgeHit> firstHits = List.of(
                new KnowledgeSearchPort.KnowledgeHit("Objective 4", "2025OKR-饶赛杰.xlsx"));
        List<KnowledgeSearchPort.KnowledgeHit> retryHits = List.of(
                new KnowledgeSearchPort.KnowledgeHit("Objective 1", "2025OKR-饶赛杰.xlsx"),
                new KnowledgeSearchPort.KnowledgeHit("Objective 2", "2025OKR-饶赛杰.xlsx"),
                new KnowledgeSearchPort.KnowledgeHit("Objective 3", "2025OKR-饶赛杰.xlsx"),
                new KnowledgeSearchPort.KnowledgeHit("Objective 4", "2025OKR-饶赛杰.xlsx"));
        when(knowledgeSearchPort.search(plan.standaloneQuery())).thenReturn(firstHits);
        when(ragRetrievalPlanner.retryQuery("肯定有前三个啊", plan, firstHits))
                .thenReturn(Optional.of("饶赛杰2025年 Objective 1 Objective 2 Objective 3"));
        when(knowledgeSearchPort.search("饶赛杰2025年 Objective 1 Objective 2 Objective 3"))
                .thenReturn(retryHits);
        Prompt modelPrompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(eq(history), anyList(), eq("肯定有前三个啊"))).thenReturn(modelPrompt);
        when(streamingChatClient.stream(modelPrompt)).thenReturn(Flux.just("完整回答"));

        StepVerifier.create(chatAppService.chat(1L, "肯定有前三个啊"))
                .expectNext(ChatStreamEvent.message("完整回答"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.files().equals(List.of("2025OKR-饶赛杰.xlsx")))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeSearchPort.KnowledgeHit>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragPromptBuilder).build(eq(history), hitsCaptor.capture(), eq("肯定有前三个啊"));
        assertThat(hitsCaptor.getValue()).extracting(KnowledgeSearchPort.KnowledgeHit::content)
                .containsExactly("Objective 4", "Objective 1", "Objective 2", "Objective 3");
        verify(knowledgeSearchPort, times(2)).search(anyString());
    }

    @Test
    void chatShouldSkipKnowledgeSearchForGeneralConversation() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        when(ragRetrievalPlanner.plan(List.of(), "你好")).thenReturn(
                new RagRetrievalPlanner.RetrievalPlan(false, "", "SINGLE"));
        Prompt modelPrompt = new Prompt(List.of(new UserMessage("你好")));
        when(ragPromptBuilder.build(List.of(), List.of(), "你好")).thenReturn(modelPrompt);
        when(streamingChatClient.stream(modelPrompt)).thenReturn(Flux.just("你好"));

        StepVerifier.create(chatAppService.chat(1L, "你好"))
                .expectNext(ChatStreamEvent.message("你好"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.answerSource().equals("MODEL_GENERAL"))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(knowledgeSearchPort, never()).search(anyString());
    }

    @Test
    void chatShouldEmitKnowledgeSearchFailedOnError() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search("问题")).thenThrow(new RuntimeException("向量库故障"));

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("KNOWLEDGE_SEARCH_FAILED"))
                .verifyComplete();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldEmitModelStreamFailedOnModelError() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search("问题")).thenReturn(List.of(
                new KnowledgeSearchPort.KnowledgeHit("片段A", "a.pdf")));
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.error(new RuntimeException("模型故障")));

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("MODEL_STREAM_FAILED"))
                .verifyComplete();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldNotSaveAssistantWhenClientCancels() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search("问题")).thenReturn(List.of(
                new KnowledgeSearchPort.KnowledgeHit("片段A", "a.pdf")));
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.never());

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .thenCancel()
                .verify();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldTreatEmptyModelStreamAsModelError() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search("问题")).thenReturn(List.of(
                new KnowledgeSearchPort.KnowledgeHit("片段A", "a.pdf")));
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.empty());

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("MODEL_STREAM_FAILED"))
                .verifyComplete();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldValidateSessionIdAndPrompt() {
        assertThatThrownBy(() -> chatAppService.chat(null, "问题"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> chatAppService.chat(1L, "   "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void chatShouldThrowBiz404WhenSessionMissing() {
        when(sessionQueryPort.exists(99L)).thenReturn(false);

        assertThatThrownBy(() -> chatAppService.chat(99L, "问题"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
    }

    private void stubSessionBasics(Long sessionId, List<MessageDto> history) {
        when(sessionQueryPort.exists(sessionId)).thenReturn(true);
        when(sessionQueryPort.listMessages(sessionId)).thenReturn(history);
    }

    private List<MessageDto> twelveMessages() {
        List<MessageDto> messages = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            messages.add(new MessageDto(i, 1L, i % 2 == 1 ? MessageType.USER : MessageType.ASSISTANT,
                    "消息" + i, null, "2026-08-26T10:00"));
        }
        return messages;
    }
}
