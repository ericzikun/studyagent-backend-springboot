package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题库通行证的账号视图，供前端展示（到期日、是否有效），不参与放行判定。
 * 放行只看 {@link SubscriptionResult#getCanReadStudyLibrary()}。
 */
@Data
@Builder
public class StudyPassAccount {
    /** 当前是否存在未过期且 status=active 的通行证。 */
    private Boolean active;
    /** one_time for legacy purchases; subscription for recurring Passes. */
    private String billingType;
    private Boolean cancelAtPeriodEnd;
    private Boolean manageable;
    private Integer renewalPriceCents;
    private String currency;
    private Integer billingIntervalDays;

    /** 最近一次购买的开始时间。 */
    private LocalDateTime startedAt;
    /** 最近一次购买的到期时间；已过期时仍保留，供前端展示。 */
    private LocalDateTime expiresAt;
    /** 兼容旧客户端，现始终为 false；会员按正常售价购买。 */
    private Boolean upgradeCreditAvailable;
    /**
     * 当前是否处于"到期后才能再次购买"的窗口。前端据此禁用购买入口，
     * 而不是自己按时间推算。
     */
    private Boolean purchasable;
}
