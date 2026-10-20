package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批过期扫描器：没有浏览器连接时也会收敛等待状态，避免会话永久被占用。
 *
 * @author raosaijie
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentApprovalExpiryScheduler {

    private final AgentApprovalService approvalService;
    private final AgentRunEventHub eventHub;

    @PostConstruct
    public void expireOnStartup() {
        expireNow();
    }

    @Scheduled(fixedDelayString = "${fileagent.agent.approval-expiry-scan-interval:30s}")
    public void expireNow() {
        try {
            for (AgentApprovalService.ExpirationOutcome outcome : approvalService.expirePendingApprovals()) {
                eventHub.publish(AgentRunEvent.status(outcome.run().getRunId(), AgentRunStatus.TIMED_OUT,
                        "审批已过期"), Math.max(0L, outcome.run().getVersion()));
            }
        } catch (RuntimeException exception) {
            log.warn("Agent 审批过期扫描失败: {}", exception.getMessage());
        }
    }
}
