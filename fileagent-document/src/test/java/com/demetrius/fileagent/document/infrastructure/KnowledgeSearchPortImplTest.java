package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeSearchPortImpl} 端口测试：参数校验、检索请求构造与结果映射。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchPortImplTest {

    @Mock
    private SimpleVectorStore vectorStore;

    @InjectMocks
    private KnowledgeSearchPortImpl knowledgeSearchPort;

    @Test
    void searchShouldRejectBlankQuery() {
        ReflectionTestUtils.setField(knowledgeSearchPort, "topK", 5);
        ReflectionTestUtils.setField(knowledgeSearchPort, "similarityThreshold", 0.7);

        assertThatThrownBy(() -> knowledgeSearchPort.search("  "))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> knowledgeSearchPort.search(null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void searchShouldBuildRequestWithConfiguredTopKAndThreshold() {
        ReflectionTestUtils.setField(knowledgeSearchPort, "topK", 5);
        ReflectionTestUtils.setField(knowledgeSearchPort, "similarityThreshold", 0.7);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        String query = "年假如何申请？";

        knowledgeSearchPort.search(query);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo(query);
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.7);
        // 全局检索不得按 knowledge/tag/sessionId 设置过滤表达式
        assertThat(request.getFilterExpression()).isNull();
    }

    @Test
    void searchShouldMapTextAndFilenameToKnowledgeHit() {
        ReflectionTestUtils.setField(knowledgeSearchPort, "topK", 5);
        ReflectionTestUtils.setField(knowledgeSearchPort, "similarityThreshold", 0.7);
        Document doc = new Document("年假为 10 天。", Map.of("filename", "员工手册.pdf"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeSearchPort.KnowledgeHit> hits = knowledgeSearchPort.search("年假如何申请？");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).isEqualTo("年假为 10 天。");
        assertThat(hits.get(0).filename()).isEqualTo("员工手册.pdf");
    }

    @Test
    void searchShouldReturnRawContentInsteadOfEmbeddingContext() {
        ReflectionTestUtils.setField(knowledgeSearchPort, "topK", 12);
        ReflectionTestUtils.setField(knowledgeSearchPort, "similarityThreshold", 0.55);
        String rawContent = "[OKR] Objective 1 | 快递产品线需求日常开发维护";
        Document doc = new Document(
                "知识库: 个人年度目标\n标签: OKR\n文件: 2025OKR-饶赛杰.xlsx\n内容: " + rawContent,
                Map.of("filename", "2025OKR-饶赛杰.xlsx", "rawContent", rawContent));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeSearchPort.KnowledgeHit> hits = knowledgeSearchPort.search("饶赛杰2025年的目标");

        assertThat(hits.getFirst().content()).isEqualTo(rawContent);
    }

    @Test
    void searchShouldPropagateVectorStoreExceptionAsIs() {
        ReflectionTestUtils.setField(knowledgeSearchPort, "topK", 5);
        ReflectionTestUtils.setField(knowledgeSearchPort, "similarityThreshold", 0.7);
        IllegalStateException failure = new IllegalStateException("向量库故障");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> knowledgeSearchPort.search("年假如何申请？"))
                .isSameAs(failure);
    }
}
