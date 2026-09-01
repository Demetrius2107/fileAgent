package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion 排名融合。
 *
 * @author raosaijie
 */
@Component
public class RrfFusion {

    public List<KnowledgeHit> fuse(List<KnowledgeHit> bm25Hits,
                                   List<KnowledgeHit> knnHits,
                                   int rankConstant) {
        if (rankConstant <= 0) {
            throw new IllegalArgumentException("rankConstant 必须大于 0");
        }
        Map<String, KnowledgeHit> candidates = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        accumulate(bm25Hits, rankConstant, candidates, scores);
        accumulate(knnHits, rankConstant, candidates, scores);

        List<KnowledgeHit> fused = new ArrayList<>(candidates.size());
        for (Map.Entry<String, KnowledgeHit> entry : candidates.entrySet()) {
            KnowledgeHit hit = entry.getValue();
            fused.add(new KnowledgeHit(
                    hit.chunkId(), hit.fileId(), hit.content(), hit.filename(),
                    hit.sheetName(), hit.sectionId(), hit.chunkIndex(), scores.get(entry.getKey())));
        }
        fused.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed()
                .thenComparing(KnowledgeHit::chunkId));
        return List.copyOf(fused);
    }

    private void accumulate(List<KnowledgeHit> hits, int rankConstant,
                            Map<String, KnowledgeHit> candidates,
                            Map<String, Double> scores) {
        if (hits == null) {
            return;
        }
        Map<String, Boolean> seenInRoute = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit hit = hits.get(i);
            if (hit == null || hit.chunkId() == null
                    || seenInRoute.putIfAbsent(hit.chunkId(), Boolean.TRUE) != null) {
                continue;
            }
            candidates.putIfAbsent(hit.chunkId(), hit);
            scores.merge(hit.chunkId(), 1.0 / (rankConstant + i + 1), Double::sum);
        }
    }
}
