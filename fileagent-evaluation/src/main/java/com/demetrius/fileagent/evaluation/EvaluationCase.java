package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;

import java.util.List;

/**
 * 一道标准评测题及其期望结果。
 *
 * @author raosaijie
 */
public record EvaluationCase(
        String schemaVersion,
        String id,
        String category,
        List<String> tags,
        String question,
        List<HistoryMessage> history,
        Filters filters,
        Expected expected
) {

    public EvaluationCase {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        id = requireText(id, "id");
        category = requireText(category, "category");
        question = requireText(question, "question");
        tags = tags == null ? List.of() : List.copyOf(tags);
        history = history == null ? List.of() : List.copyOf(history);
        filters = filters == null ? new Filters(null, null, null) : filters;
        if (expected == null) {
            throw new IllegalArgumentException("expected 不能为空");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 多轮问题的历史消息。
     *
     * @author raosaijie
     */
    public record HistoryMessage(String role, String content) {
        public HistoryMessage {
            role = requireText(role, "history.role").toUpperCase(java.util.Locale.ROOT);
            content = requireText(content, "history.content");
            if (!role.equals("USER") && !role.equals("ASSISTANT")) {
                throw new IllegalArgumentException("history.role 仅支持 USER 或 ASSISTANT");
            }
        }
    }

    /**
     * 检索范围过滤条件。
     *
     * @author raosaijie
     */
    public record Filters(String ragName, String knowledgeTag, Long fileId) {
    }

    /**
     * 标准答案约束。自适应检索期望（{@code expectedQueryType}/{@code expectedSubQuestions}）
     * 仅用于 adaptive-v1 数据集的人工标注：前者核对查询类型准确率与不必要检索率，
     * 后者核对多跳/比较题的子问题覆盖。
     */
    public record Expected(
            Boolean shouldAnswer,
            List<ExpectedSource> relevantSources,
            List<String> requiredFacts,
            List<String> forbiddenFacts,
            AnswerGroundingMode groundingMode,
            String expectedQueryType,
            List<String> expectedSubQuestions
    ) {

        public Expected(Boolean shouldAnswer,
                        List<ExpectedSource> relevantSources,
                        List<String> requiredFacts,
                        List<String> forbiddenFacts,
                        AnswerGroundingMode groundingMode) {
            this(shouldAnswer, relevantSources, requiredFacts, forbiddenFacts, groundingMode,
                    null, null);
        }

        public Expected(Boolean shouldAnswer,
                        List<ExpectedSource> relevantSources,
                        List<String> requiredFacts,
                        List<String> forbiddenFacts) {
            this(shouldAnswer, relevantSources, requiredFacts, forbiddenFacts, null);
        }

        public Expected {
            shouldAnswer = shouldAnswer == null ? Boolean.TRUE : shouldAnswer;
            relevantSources = relevantSources == null ? List.of() : List.copyOf(relevantSources);
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            forbiddenFacts = forbiddenFacts == null ? List.of() : List.copyOf(forbiddenFacts);
            groundingMode = groundingMode == null
                    ? (shouldAnswer ? AnswerGroundingMode.KNOWLEDGE_BASED : AnswerGroundingMode.REFUSE)
                    : groundingMode;
            if (expectedQueryType != null && expectedQueryType.isBlank()) {
                throw new IllegalArgumentException("expected.expectedQueryType 不能为空白");
            }
            expectedQueryType = parseQueryType(expectedQueryType);
            expectedSubQuestions = expectedSubQuestions == null
                    ? List.of() : List.copyOf(expectedSubQuestions);
            if (groundingMode.shouldAnswer() != shouldAnswer) {
                throw new IllegalArgumentException("expected.groundingMode 与 shouldAnswer 不一致");
            }
        }

        private static String parseQueryType(String value) {
            if (value == null) {
                return null;
            }
            try {
                return RetrievalQueryType.valueOf(value.trim()).name();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("expected.expectedQueryType 非法: " + value);
            }
        }
    }

    /**
     * 可定位到文件、工作表、章节或切片的相关来源。
     *
     * @author raosaijie
     */
    public record ExpectedSource(
            String chunkId,
            Long fileId,
            String filename,
            String sheetName,
            String sectionId,
            Integer chunkIndex,
            Integer relevance
    ) {
        public ExpectedSource {
            relevance = relevance == null ? 1 : relevance;
            if (relevance < 1 || relevance > 3) {
                throw new IllegalArgumentException("relevance 必须在 1 到 3 之间");
            }
            if (chunkId == null && fileId == null && filename == null && sheetName == null
                    && sectionId == null && chunkIndex == null) {
                throw new IllegalArgumentException("相关来源至少需要一个定位字段");
            }
        }

        boolean matches(EvaluationObservation.ObservedSource source) {
            return matchesIfPresent(chunkId, source.chunkId())
                    && matchesIfPresent(fileId, source.fileId())
                    && matchesIfPresent(filename, source.filename())
                    && matchesIfPresent(sheetName, source.sheetName())
                    && matchesIfPresent(sectionId, source.sectionId())
                    && matchesIfPresent(chunkIndex, source.chunkIndex());
        }

        boolean matchesCitation(EvaluationObservation.ObservedSource source) {
            if (filename != null) {
                return filename.equals(source.filename());
            }
            if (fileId != null) {
                return fileId.equals(source.fileId());
            }
            if (sheetName != null) {
                return sheetName.equals(source.sheetName());
            }
            if (chunkId != null) {
                return chunkId.equals(source.chunkId());
            }
            if (sectionId != null) {
                return sectionId.equals(source.sectionId());
            }
            return chunkIndex != null && chunkIndex.equals(source.chunkIndex());
        }

        String citationIdentity() {
            if (filename != null) {
                return "filename:" + filename;
            }
            if (fileId != null) {
                return "fileId:" + fileId;
            }
            if (sheetName != null) {
                return "sheetName:" + sheetName;
            }
            if (chunkId != null) {
                return "chunkId:" + chunkId;
            }
            if (sectionId != null) {
                return "sectionId:" + sectionId;
            }
            return "chunkIndex:" + chunkIndex;
        }

        int specificity() {
            int count = 0;
            count += chunkId == null ? 0 : 1;
            count += fileId == null ? 0 : 1;
            count += filename == null ? 0 : 1;
            count += sheetName == null ? 0 : 1;
            count += sectionId == null ? 0 : 1;
            count += chunkIndex == null ? 0 : 1;
            return count;
        }

        private static boolean matchesIfPresent(Object expected, Object actual) {
            return expected == null || expected.equals(actual);
        }
    }
}
