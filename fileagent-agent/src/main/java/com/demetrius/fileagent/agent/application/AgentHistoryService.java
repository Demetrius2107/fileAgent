package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.port.HistorySummaryPort;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.run.AgentRunBudget;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Agent 历史上下文准备服务：选择完整最近消息，并在超限时滚动压缩旧消息。
 *
 * @author raosaijie
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHistoryService {

    private final HistorySummaryPort historySummaryPort;
    private final SessionQueryPort sessionQueryPort;
    private final SessionMessagePort sessionMessagePort;

    public PreparedHistory prepare(AgentRun run, AgentRunBudget budget, AgentRunCommand command) {
        String existingSummary = command.historySummary();
        List<MessageDto> history = command.history() == null ? List.of() : List.copyOf(command.history());
        List<MessageDto> recent = selectRecent(history, budget.maxRecentHistoryCharacters());
        List<MessageDto> old = history.subList(0, history.size() - recent.size());
        int existingSummaryCharacters = length(existingSummary);
        int currentHistoryCharacters = totalCharacters(recent);

        if (old.isEmpty() && existingSummaryCharacters + currentHistoryCharacters <= budget.maxHistoryCharacters()) {
            run.recordHistoryUsage(existingSummaryCharacters + currentHistoryCharacters,
                    existingSummaryCharacters, false);
            return new PreparedHistory(existingSummary, recent, false, command.historySummaryThroughMessageId());
        }

        if (old.isEmpty()) {
            old = recent;
            recent = List.of();
        }

        List<MessageDto> summaryBatch = fitFromStart(old,
                Math.max(1, budget.maxHistoryCharacters() - Math.min(existingSummaryCharacters,
                        budget.maxSummaryCharacters())));
        if (summaryBatch.isEmpty()) {
            run.addBudgetReason("SUMMARY_FAILED_FALLBACK");
            run.recordHistoryUsage(existingSummaryCharacters + currentHistoryCharacters,
                    existingSummaryCharacters, false);
            return new PreparedHistory(existingSummary, recent, false, command.historySummaryThroughMessageId());
        }

        String summary = existingSummary;
        try {
            if (run.modelCallCount() >= budget.maxModelCalls()) {
                throw new IllegalStateException("摘要模型调用预算已耗尽");
            }
            run.incrementModelCall();
            HistorySummaryPort.SummaryResult result = historySummaryPort.summarize(existingSummary, summaryBatch);
            if (result == null || result.summary() == null || result.summary().isBlank()
                    || length(result.summary()) > budget.maxSummaryCharacters()) {
                throw new IllegalStateException("摘要结果为空或超出长度预算");
            }
            summary = result.summary();
            run.recordModelUsage(result.inputTokens(), result.outputTokens(), result.totalTokens());
            run.addBudgetReason("HISTORY_SUMMARIZED");
            summary = persistSummary(command, summary, summaryBatch.getLast().id(), run);
        } catch (Exception e) {
            log.warn("历史摘要失败 runId={}: {}", command.runId(), e.getMessage());
            run.addBudgetReason("SUMMARY_FAILED_FALLBACK");
        }

        int summaryCharacters = length(summary);
        int finalHistoryCharacters = summaryCharacters + totalCharacters(recent);
        run.recordHistoryUsage(finalHistoryCharacters, summaryCharacters, !summaryBatch.isEmpty());
        return new PreparedHistory(summary, recent, true, summaryBatch.getLast().id());
    }

    private String persistSummary(AgentRunCommand command, String summary, Long checkpoint, AgentRun run) {
        if (command.sessionId() == null || checkpoint == null) {
            return summary;
        }
        boolean updated = sessionMessagePort.updateSummary(
                command.sessionId(), command.historySummaryVersion(), summary, checkpoint,
                sourceHash(summary, checkpoint));
        if (!updated) {
            run.addBudgetReason("SUMMARY_CONCURRENT_UPDATE");
            SessionQueryPort.SessionSummary latest = sessionQueryPort.getSummary(command.sessionId());
            return latest == null || latest.content() == null ? summary : latest.content();
        }
        return summary;
    }

    private List<MessageDto> selectRecent(List<MessageDto> history, int maxCharacters) {
        List<MessageDto> selected = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageDto message = history.get(i);
            int size = length(message == null ? null : message.content());
            if (!selected.isEmpty() && used + size > maxCharacters) {
                break;
            }
            if (size > maxCharacters && selected.isEmpty()) {
                break;
            }
            selected.add(0, message);
            used += size;
        }
        return selected;
    }

    private List<MessageDto> fitFromStart(List<MessageDto> messages, int maxCharacters) {
        List<MessageDto> selected = new ArrayList<>();
        int used = 0;
        for (MessageDto message : messages) {
            int size = length(message == null ? null : message.content());
            if (used + size > maxCharacters) {
                break;
            }
            selected.add(message);
            used += size;
        }
        return selected;
    }

    private int totalCharacters(List<MessageDto> messages) {
        return messages.stream().mapToInt(message -> length(message == null ? null : message.content())).sum();
    }

    private int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private String sourceHash(String summary, Long checkpoint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((checkpoint + "\n" + summary).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record PreparedHistory(String summary,
                                  List<MessageDto> recentMessages,
                                  boolean compressed,
                                  Long summaryThroughMessageId) {
        public PreparedHistory(String summary, List<MessageDto> recentMessages, boolean compressed) {
            this(summary, recentMessages, compressed, null);
        }

        public PreparedHistory {
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        }
    }
}
