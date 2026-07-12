package com.studyagent.service.domain.quota;

/**
 * Persistence for {@code quota_vip_users}.
 * <p>
 * Active VIP means: {@code status = active} and ({@code expires_at IS NULL} or {@code expires_at > now}).
 */
public interface QuotaVipRepository {

    /**
     * @return true if the clerk user currently has an active VIP row
     */
    boolean existsActiveVip(String clerkUserId);
}
