package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import com.demetrius.fileagent.common.exception.BizException;
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
 * {@link ChatAppServiceImpl} 编排测试。
 *
 * @author raosaijie
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
    private RagQueryRewriter ragQueryRewriter;
    @Mock
    private StreamingChatClient streamingChatClient;

    @InjectMocks
    private ChatAppServiceImpl chatAppService;

    @Captor
    private ArgumentCaptor<List<MessageDto>> historyCaptor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatAppService, "chatHistoryLimit", 10);
        lenient().when(ragQueryRewriter.rewrite(anyList(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
                hit("1:0", "片段A", "a.pdf"),
                hit("2:0", "片段B", "b.pdf"),
                hit("1:1", "片段C", "a.pdf"));
        when(knowledgeSearchPort.search(query("问题"))).thenReturn(hits);
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), eq(hits), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("回答", "内容")
                .doOnSubscribe(subscription -> {
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
        assertThat(historyCaptor.getValue().getFirst().id()).isEqualTo(3L);
        InOrder inOrder = inOrder(sessionQueryPort, sessionMessagePort);
        inOrder.verify(sessionQueryPort).listMessages(1L);
        inOrder.verify(sessionMessagePort).append(1L, MessageType.USER, "问题");
        verify(sessionMessagePort, times(1)).append(1L, MessageType.ASSISTANT, "回答内容");
    }

    @Test
    void chatShouldUseRewrittenQueryForMultiTurnRetrieval() {
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "2025年研发部有哪些目标", null,
                        "2026-08-28T10:00"));
        stubSessionBasics(1L, history);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        when(ragQueryRewriter.rewrite(history, "那2026年呢"))
                .thenReturn("2026年研发部有哪些目标");
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                hit("1:0", "目标一", "年度计划.xlsx"),
                hit("1:1", "目标二", "年度计划.xlsx"));
        when(knowledgeSearchPort.search(query("2026年研发部有哪些目标"))).thenReturn(hits);
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(history, hits, "那2026年呢")).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("完整回答"));

        StepVerifier.create(chatAppService.chat(1L, "那2026年呢"))
                .expectNext(ChatStreamEvent.message("完整回答"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.files().equals(List.of("年度计划.xlsx")))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(knowledgeSearchPort).search(query("2026年研发部有哪些目标"));
    }

    @Test
    void chatShouldPrependNoticeAndSaveItIntoAssistantOnKnowledgeMiss() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        when(knowledgeSearchPort.search(query("制度问题"))).thenReturn(List.of());
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("制度问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("通用", "回答"));

        StepVerifier.create(chatAppService.chat(1L, "制度问题"))
                .expectNext(ChatStreamEvent.message(NO_KNOWLEDGE_NOTICE))
                .expectNext(ChatStreamEvent.message("通用"))
                .expectNext(ChatStreamEvent.message("回答"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.answerSource().equals("MODEL_GENERAL"))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(sessionMessagePort).append(1L, MessageType.ASSISTANT,
                NO_KNOWLEDGE_NOTICE + "\n通用回答");
    }

    @Test
    void chatShouldRetrieveKnowledgeEvenForGeneralConversation() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), anyString())).thenReturn(101L);
        when(knowledgeSearchPort.search(query("你好"))).thenReturn(List.of());
        Prompt prompt = new Prompt(List.of(new UserMessage("你好")));
        when(ragPromptBuilder.build(List.of(), List.of(), "你好")).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(Flux.just("你好"));

        StepVerifier.create(chatAppService.chat(1L, "你好"))
                .expectNext(ChatStreamEvent.message(NO_KNOWLEDGE_NOTICE))
                .expectNext(ChatStreamEvent.message("你好"))
                .expectNextMatches(event -> event.type().equals("sources")
                        && event.answerSource().equals("MODEL_GENERAL"))
                .expectNext(ChatStreamEvent.done(101L))
                .verifyComplete();

        verify(knowledgeSearchPort).search(query("你好"));
    }

    @Test
    void chatShouldEmitKnowledgeSearchFailedOnError() {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search(query("问题")))
                .thenThrow(new RuntimeException("检索故障"));

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("KNOWLEDGE_SEARCH_FAILED"))
                .verifyComplete();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldEmitModelStreamFailedOnModelError() {
        stubModelFailureScenario(Flux.error(new RuntimeException("模型故障")));

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("MODEL_STREAM_FAILED"))
                .verifyComplete();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldNotSaveAssistantWhenClientCancels() {
        stubModelFailureScenario(Flux.never());

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .thenCancel()
                .verify();

        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
    }

    @Test
    void chatShouldTreatEmptyModelStreamAsModelError() {
        stubModelFailureScenario(Flux.empty());

        StepVerifier.create(chatAppService.chat(1L, "问题"))
                .expectNextMatches(event -> event.type().equals("error")
                        && event.code().equals("MODEL_STREAM_FAILED"))
                .verifyComplete();
    }

    @Test
    void chatShouldValidateInputAndMissingSession() {
        assertThatThrownBy(() -> chatAppService.chat(null, "问题"))
                .isInstanceOf(BizException.class).hasMessageContaining("sessionId");
        assertThatThrownBy(() -> chatAppService.chat(1L, "   "))
                .isInstanceOf(BizException.class).hasMessageContaining("prompt");
        when(sessionQueryPort.exists(99L)).thenReturn(false);
        assertThatThrownBy(() -> chatAppService.chat(99L, "问题"))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(404));
    }

    private void stubModelFailureScenario(Flux<String> modelStream) {
        stubSessionBasics(1L, List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), anyString())).thenReturn(100L);
        when(knowledgeSearchPort.search(query("问题")))
                .thenReturn(List.of(hit("1:0", "片段A", "a.pdf")));
        Prompt prompt = new Prompt(List.of(new UserMessage("组好的 Prompt")));
        when(ragPromptBuilder.build(anyList(), anyList(), eq("问题"))).thenReturn(prompt);
        when(streamingChatClient.stream(prompt)).thenReturn(modelStream);
    }

    private KnowledgeSearchPort.SearchQuery query(String text) {
        return new KnowledgeSearchPort.SearchQuery(text, null, null, null);
    }

    private KnowledgeSearchPort.KnowledgeHit hit(String id, String content, String filename) {
        return new KnowledgeSearchPort.KnowledgeHit(
                id, 1L, content, filename, null, "section", null, 0, 1.0);
    }

    private void stubSessionBasics(Long sessionId, List<MessageDto> history) {
        when(sessionQueryPort.exists(sessionId)).thenReturn(true);
        when(sessionQueryPort.listMessages(sessionId)).thenReturn(history);
    }

    private List<MessageDto> twelveMessages() {
        List<MessageDto> messages = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            messages.add(new MessageDto(i, 1L,
                    i % 2 == 1 ? MessageType.USER : MessageType.ASSISTANT,
                    "消息" + i, null, "2026-08-26T10:00"));
        }
        return messages;
    }
}
