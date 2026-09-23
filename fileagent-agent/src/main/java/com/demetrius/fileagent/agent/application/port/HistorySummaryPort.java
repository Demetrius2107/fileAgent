package com.demetrius.fileagent.agent.application.port;

import com.demetrius.fileagent.api.dto.MessageDto;

import java.util.List;

/**
 * 历史摘要模型端口。
 *
 * @author raosaijie
 */
public interface HistorySummaryPort {

    SummaryResult summarize(String existingSummary, List<MessageDto> messages);

    record SummaryResult(String summary, int inputTokens, int outputTokens, int totalTokens) {
    }
}
