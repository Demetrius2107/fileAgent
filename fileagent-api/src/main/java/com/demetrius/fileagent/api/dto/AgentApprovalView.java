package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;

import java.time.Instant;

/**
 * Agent 审批的公开脱敏视图，仅提供用户作出当前一次决定所需的安全摘要。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public record AgentApprovalView(
        String approvalId,
        String tool,
        ActionRiskLevel riskLevel,
        String actionSummary,
        String resourceScope,
        String expectedImpact,
        String parameterPreview,
        String parameterFingerprint,
        Instant expiresAt,
        ApprovalStatus status) {
}
