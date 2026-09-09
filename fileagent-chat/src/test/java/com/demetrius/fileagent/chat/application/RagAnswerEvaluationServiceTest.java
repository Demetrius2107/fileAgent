package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerEvaluationPort;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.chat.infrastructure.StreamingChatClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagAnswerEvaluationServiceTest {

    @Mock
    private KnowledgeSearchPort knowledgeSearchPort;
    @Mock
    private RagQueryRewriter ragQueryRewriter;
    @Mock
    private RagPromptBuilder ragPromptBuilder;
    @Mock
    private StreamingChatClient streamingChatClient;

    @InjectMocks
    private RagAnswerEvaluationService service;

    @Test
    void shouldRunCurrentRagChainWithoutSessionPersistence() {
        RagAnswerEvaluationPort.Query query = new RagAnswerEvaluationPort.Query(
                "那今年呢？",
                List.of(new RagAnswerEvaluationPort.HistoryMessage(MessageType.USER, "去年年假有几天？")),
                "fileagent-eval-v1", "baseline", null);
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                hit("1:0", "employee-handbook.md", "年假 5 天"),
                hit("1:1", "employee-handbook.md", "提前 3 天申请"),
                hit("2:0", "other.md", "其他资料"));
        when(ragQueryRewriter.rewrite(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("那今年呢？"))).thenReturn("今年年假有几天？");
        when(knowledgeSearchPort.search(new KnowledgeSearchPort.SearchQuery(
                "今年年假有几天？", "fileagent-eval-v1", "baseline", null))).thenReturn(hits);
        Prompt prompt = new Prompt(List.of(new UserMessage("组装后的 Prompt")));
        when(ragPromptBuilder.build(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(hits), org.mockito.ArgumentMatchers.eq("那今年呢？")))
                .thenReturn(prompt);
        when(streamingChatClient.call(prompt)).thenReturn("员工入职第一年享有年假 5 天。");

        RagAnswerEvaluationPort.Result result = service.evaluate(query);

        assertThat(result.answer()).isEqualTo("员工入职第一年享有年假 5 天。");
        assertThat(result.retrieved()).isEqualTo(hits);
        assertThat(result.citations()).extracting(KnowledgeSearchPort.KnowledgeHit::filename)
                .containsExactly("employee-handbook.md", "other.md");
        verify(streamingChatClient).call(prompt);
    }

    private static KnowledgeSearchPort.KnowledgeHit hit(String chunkId, String filename, String content) {
        return new KnowledgeSearchPort.KnowledgeHit(
                chunkId, 1L, content, filename, null, "document", null, 0, 0.9);
    }
}
