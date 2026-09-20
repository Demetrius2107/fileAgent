package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link RrfFusion} 测试。
 *
 * @author raosaijie
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    @Test
    void fuseShouldMergeTwoRoutesByReciprocalRank() {
        List<KnowledgeHit> bm25 = List.of(hit("A"), hit("B"), hit("C"));
        List<KnowledgeHit> knn = List.of(hit("C"), hit("A"), hit("D"));

        List<KnowledgeHit> result = fusion.fuse(bm25, knn, 60);

        assertThat(result).extracting(KnowledgeHit::chunkId)
                .containsExactly("A", "C", "B", "D");
        assertThat(result.getFirst().score()).isGreaterThan(result.get(2).score());
    }

    @Test
    void fuseShouldHandleEmptyAndSingleRoute() {
        assertThat(fusion.fuse(List.of(), List.of(), 60)).isEmpty();
        assertThat(fusion.fuse(List.of(hit("A"), hit("B")), List.of(), 60))
                .extracting(KnowledgeHit::chunkId)
                .containsExactly("A", "B");
    }

    @Test
    void fuseShouldCountDuplicateOnlyOncePerRoute() {
        List<KnowledgeHit> result = fusion.fuse(
                List.of(hit("A"), hit("A")), List.of(hit("B")), 60);

        assertThat(result).extracting(KnowledgeHit::chunkId)
                .containsExactly("A", "B");
    }

    @Test
    void fuseShouldRejectInvalidRankConstant() {
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightedFuseShouldScaleRoutesByWeights() {
        List<KnowledgeHit> result = fusion.fuse(
                List.of(hit("A")), List.of(hit("B")), 60, 1.3, 0.7);

        assertThat(result).extracting(KnowledgeHit::chunkId)
                .containsExactly("A", "B");
        assertThat(result.getFirst().score()).isCloseTo(1.3 / 61, within(1e-9));
        assertThat(result.get(1).score()).isCloseTo(0.7 / 61, within(1e-9));
    }

    @Test
    void weightedFuseWithDefaultWeightsShouldMatchEqualPath() {
        List<KnowledgeHit> bm25 = List.of(hit("A"), hit("B"), hit("C"));
        List<KnowledgeHit> knn = List.of(hit("C"), hit("A"), hit("D"));

        List<KnowledgeHit> equal = fusion.fuse(bm25, knn, 60);
        List<KnowledgeHit> weighted = fusion.fuse(bm25, knn, 60, 1.0, 1.0);

        assertThat(weighted).isEqualTo(equal);
    }

    @Test
    void weightedFuseShouldRejectNonPositiveWeights() {
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 60, 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 60, 1.0, -0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeHit hit(String id) {
        return new KnowledgeHit(id, 1L, id, "file.txt", null, "section", null, 0, 0);
    }
}
