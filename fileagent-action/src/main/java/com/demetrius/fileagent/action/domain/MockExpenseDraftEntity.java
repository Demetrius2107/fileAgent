package com.demetrius.fileagent.action.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仅用于本地动作验收的报销草稿，不保存账户原文。
 *
 * @author raosaijie
 */
@Entity
@Table(name = "action_mock_expense_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockExpenseDraftEntity {

    /** 草稿标识。 */
    @Id
    @Column(length = 36, nullable = false)
    private String draftId;

    /** 远端幂等键，在数据库中唯一。 */
    @Column(length = 128, nullable = false, unique = true)
    private String idempotencyKey;

    /** 包含敏感参数的规范化请求的 HMAC，不可还原原文。 */
    @Column(length = 64, nullable = false)
    private String requestHmac;

    /** 测试报销金额。 */
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 测试报销备注。 */
    @Column(length = 500, nullable = false)
    private String memo;

    /** 草稿创建时间。 */
    @Column(nullable = false)
    private Instant createdAt;

    /** 草稿最近更新时间。 */
    @Column(nullable = false)
    private Instant updatedAt;

    public MockExpenseDraftEntity(String draftId, String idempotencyKey, String requestHmac,
                                  BigDecimal amount, String memo, Instant now) {
        this.draftId = draftId;
        this.idempotencyKey = idempotencyKey;
        this.requestHmac = requestHmac;
        this.amount = amount;
        this.memo = memo;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
