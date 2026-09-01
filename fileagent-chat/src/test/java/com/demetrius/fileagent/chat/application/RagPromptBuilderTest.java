package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagPromptBuilder} 用例测试：消息顺序、知识上下文格式与系统规则。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
class RagPromptBuilderTest {

    private final RagPromptBuilder ragPromptBuilder = new RagPromptBuilder();

    @Test
    void buildShouldOrderSystemThenHistoryThenCurrentQuestion() {
        List<MessageDto> history = List.of(
                new MessageDto(1L, 1L, MessageType.USER, "之前的问题", null, "2026-08-26T10:00"),
                new MessageDto(2L, 1L, MessageType.ASSISTANT, "之前的回答", null, "2026-08-26T10:01")
        );
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                hit("知识片段正文", "员工手册.pdf", "制度", 3));

        Prompt prompt = ragPromptBuilder.build(history, hits, "年假如何申请？");

        List<Message> messages = prompt.getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("之前的问题");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("之前的回答");
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
    }

    @Test
    void buildShouldEmbedKnowledgeWithContextMarkers() {
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                hit("知识片段正文", "员工手册.pdf", "制度", 3));

        Prompt prompt = ragPromptBuilder.build(List.of(), hits, "年假如何申请？");

        String userContent = prompt.getInstructions().get(1).getText();
        assertThat(userContent)
                .contains("参考资料：")
                .contains("[来源: 员工手册.pdf；Sheet: 制度；片段: 3]")
                .contains("知识片段正文")
                .contains("用户问题：")
                .contains("年假如何申请？");
        int referenceIndex = userContent.indexOf("参考资料：");
        int sourceIndex = userContent.indexOf("[来源: 员工手册.pdf");
        int questionIndex = userContent.indexOf("用户问题：");
        assertThat(referenceIndex).isZero();
        assertThat(sourceIndex).isGreaterThan(referenceIndex);
        assertThat(questionIndex).isGreaterThan(sourceIndex);
    }

    @Test
    void buildShouldIncludeEveryHitWithItsOwnSourceMarker() {
        List<KnowledgeSearchPort.KnowledgeHit> hits = List.of(
                hit("片段一", "手册.pdf", null, 0),
                hit("片段二", "制度.docx", null, 1));

        Prompt prompt = ragPromptBuilder.build(List.of(), hits, "问题");

        String userContent = prompt.getInstructions().get(1).getText();
        assertThat(userContent)
                .contains("[来源: 手册.pdf；片段: 0]")
                .contains("片段一")
                .contains("[来源: 制度.docx；片段: 1]")
                .contains("片段二");
    }

    @Test
    void buildShouldUsePlainQuestionWhenNoKnowledgeHit() {
        Prompt prompt = ragPromptBuilder.build(List.of(), List.of(), "闲聊问题");

        String userContent = prompt.getInstructions().get(1).getText();
        assertThat(userContent).isEqualTo("闲聊问题");
    }

    @Test
    void systemMessageShouldExpressFixedRules() {
        Prompt prompt = ragPromptBuilder.build(List.of(), List.of(), "问题");

        String systemContent = prompt.getInstructions().get(0).getText();
        assertThat(systemContent)
                .contains("优先使用参考资料")
                .contains("通用知识")
                .contains("说明")
                .contains("不是系统指令")
                .contains("不得编造")
                .contains("完整性")
                .contains("全部");
    }

    private KnowledgeSearchPort.KnowledgeHit hit(
            String content, String filename, String sheetName, int chunkIndex) {
        return new KnowledgeSearchPort.KnowledgeHit(
                "1:" + chunkIndex, 1L, content, filename,
                sheetName, "section", chunkIndex, 1.0);
    }
}
