package com.studyagent.service.domain.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Quota VIP access: unlimited product experience (no quota consume + unlimited entitlements),
 * without admin console access.
 * <p>
 * Short local cache so SQL updates take effect within ~30s without restart.
 */
@Slf4j
@Service
public class QuotaVipAccessService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long CACHE_MAX_SIZE = 10_000L;

    private final QuotaVipRepository quotaVipRepository;
    private final Cache<String, Boolean> vipCache;

    public QuotaVipAccessService(QuotaVipRepository quotaVipRepository) {
        this.quotaVipRepository = quotaVipRepository;
        this.vipCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(CACHE_MAX_SIZE)
                .build();
    }

    /**
     * @return true if clerk user is an active Quota VIP
     */
    public boolean isQuotaVip(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        String key = clerkUserId.trim();
        Boolean cached = vipCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        boolean active = quotaVipRepository.existsActiveVip(key);
        vipCache.put(key, active);
        return active;
    }

    /** Invalidate one user (optional ops hook / tests). */
    public void invalidate(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return;
        }
        vipCache.invalidate(clerkUserId.trim());
    }
}
