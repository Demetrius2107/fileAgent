package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从回答中提取实际使用的已检索来源。
 *
 * @author raosaijie
 */
public final class AnswerCitationExtractor {

    private AnswerCitationExtractor() {
    }

    /**
     * 仅保留答案文本实际出现、且本次检索确实返回的文件名。
     *
     * @param answer 回答正文
     * @param hits   本次检索命中
     * @return 实际引用的来源，按检索结果首次出现顺序去重
     */
    public static List<KnowledgeSearchPort.KnowledgeHit> extract(
            String answer, List<KnowledgeSearchPort.KnowledgeHit> hits) {
        if (answer == null || answer.isBlank() || hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeSearchPort.KnowledgeHit> citations = new LinkedHashMap<>();
        for (KnowledgeSearchPort.KnowledgeHit hit : hits) {
            String filename = hit.filename();
            if (filename != null && !filename.isBlank() && answer.contains(filename)) {
                citations.putIfAbsent(filename, hit);
            }
        }
        return List.copyOf(citations.values());
    }
}
