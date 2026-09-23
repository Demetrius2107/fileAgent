package com.demetrius.fileagent.agent.infrastructure.tool;

/**
 * @author raosaijie
 */
final class ToolOutputBudget {

    static final String BUDGET_EXHAUSTED_MESSAGE = "知识库工具预算已用尽，请基于已有证据回答。";
    private static final String TRUNCATED = "…(截断)";

    private ToolOutputBudget() {
    }

    static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    static String truncate(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || maxCodePoints <= 0) {
            return maxCodePoints <= 0 ? "" : value == null ? "" : value;
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    static String fit(String prefix, String content, String suffix, int maxCodePoints) {
        prefix = prefix == null ? "" : prefix;
        content = content == null ? "" : content;
        suffix = suffix == null ? "" : suffix;
        if (maxCodePoints <= 0) {
            return "";
        }
        String full = prefix + content + suffix;
        if (length(full) <= maxCodePoints) {
            return full;
        }
        int available = maxCodePoints - length(prefix) - length(suffix);
        if (available <= 0) {
            return truncate(prefix + suffix, maxCodePoints);
        }
        int contentLimit = available - length(TRUNCATED);
        if (contentLimit <= 0) {
            return truncate(prefix + suffix, maxCodePoints);
        }
        return prefix + truncate(content, contentLimit) + TRUNCATED + suffix;
    }

    static boolean append(StringBuilder target, String prefix, String content,
                          String suffix, int maxCodePoints) {
        int remaining = maxCodePoints - length(target.toString());
        if (remaining <= 0) {
            return false;
        }
        String entry = fit(prefix, content, suffix, remaining);
        if (entry.isEmpty()) {
            return false;
        }
        target.append(entry);
        return true;
    }
}
