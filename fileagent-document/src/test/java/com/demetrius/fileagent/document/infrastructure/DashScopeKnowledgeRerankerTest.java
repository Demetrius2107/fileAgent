package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link DashScopeKnowledgeReranker} 测试。
 *
 * @author raosaijie
 */
class DashScopeKnowledgeRerankerTest {

    @Test
    void rerankShouldApplyProviderOrderAndScore() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerProperties properties = properties(true);
        DashScopeKnowledgeReranker reranker = new DashScopeKnowledgeReranker(builder, properties);
        server.expect(once(), requestTo(properties.getBaseUrl()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"qwen3-rerank",
                          "query":"年度目标",
                          "documents":["目标.xlsx\nOKR\n片段A","目标.xlsx\nOKR\n片段B"],
                          "top_n":2
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "results":[
                            {"index":1,"relevance_score":0.92},
                            {"index":0,"relevance_score":0.61}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<KnowledgeHit> result = reranker.rerank(
                "年度目标", List.of(hit("A"), hit("B")));

        assertThat(result).extracting(KnowledgeHit::chunkId).containsExactly("B", "A");
        assertThat(result).extracting(KnowledgeHit::score).containsExactly(0.92, 0.61);
        server.verify();
    }

    @Test
    void rerankShouldReturnOriginalCandidatesWhenDisabled() {
        RestClient.Builder builder = RestClient.builder();
        RerankerProperties properties = properties(false);
        DashScopeKnowledgeReranker reranker = new DashScopeKnowledgeReranker(builder, properties);
        List<KnowledgeHit> candidates = List.of(hit("A"), hit("B"));

        assertThat(reranker.rerank("年度目标", candidates)).isSameAs(candidates);
    }

    @Test
    void rerankShouldFilterScoresBelowMinimumAndKeepBoundary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerProperties properties = properties(true);
        DashScopeKnowledgeReranker reranker = new DashScopeKnowledgeReranker(builder, properties);
        server.expect(once(), requestTo(properties.getBaseUrl()))
                .andRespond(withSuccess("""
                        {
                          "results":[
                            {"index":0,"relevance_score":0.92},
                            {"index":1,"relevance_score":0.20},
                            {"index":2,"relevance_score":0.19}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<KnowledgeHit> result = reranker.rerank(
                "年度目标", List.of(hit("A"), hit("B"), hit("C")));

        assertThat(result).extracting(KnowledgeHit::chunkId).containsExactly("A", "B");
        assertThat(result).extracting(KnowledgeHit::score).containsExactly(0.92, 0.20);
        server.verify();
    }

    @Test
    void rerankShouldReturnEmptyWhenAllScoresAreBelowMinimum() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerProperties properties = properties(true);
        DashScopeKnowledgeReranker reranker = new DashScopeKnowledgeReranker(builder, properties);
        server.expect(once(), requestTo(properties.getBaseUrl()))
                .andRespond(withSuccess("""
                        {
                          "results":[
                            {"index":0,"relevance_score":0.19}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<KnowledgeHit> result = reranker.rerank("无答案问题", List.of(hit("A")));

        assertThat(result).isEmpty();
        server.verify();
    }

    private RerankerProperties properties(boolean enabled) {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl("https://workspace.example.com/compatible-api/v1/reranks");
        properties.setApiKey("sk-test");
        properties.setModel("qwen3-rerank");
        properties.setCandidateTopK(20);
        properties.setTopN(12);
        return properties;
    }

    private KnowledgeHit hit(String id) {
        return new KnowledgeHit(id, 1L, "片段" + id, "目标.xlsx", "OKR",
                "sheet-0-section-0", null, 0, 0.1);
    }
}
