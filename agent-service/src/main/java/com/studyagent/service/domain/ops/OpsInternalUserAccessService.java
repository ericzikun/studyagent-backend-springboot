package com.studyagent.service.domain.ops;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Cached access to Ops internal team user ids.
 * <p>
 * Short local cache so SQL updates take effect within ~30s without restart.
 */
@Slf4j
@Service
public class OpsInternalUserAccessService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String ACTIVE_IDS_CACHE_KEY = "active_ids";

    private final OpsInternalUserRepository opsInternalUserRepository;
    private final Cache<String, List<String>> activeIdsCache;
    private final Cache<String, Boolean> membershipCache;

    public OpsInternalUserAccessService(OpsInternalUserRepository opsInternalUserRepository) {
        this.opsInternalUserRepository = opsInternalUserRepository;
        this.activeIdsCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(8)
                .build();
        this.membershipCache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(10_000L)
                .build();
    }

    /**
     * @return active internal clerk user ids (never null)
     */
    public List<String> listActiveClerkUserIds() {
        List<String> cached = activeIdsCache.getIfPresent(ACTIVE_IDS_CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        List<String> ids = opsInternalUserRepository.listActiveClerkUserIds();
        List<String> safe = ids == null ? List.of() : List.copyOf(ids);
        activeIdsCache.put(ACTIVE_IDS_CACHE_KEY, safe);
        return safe;
    }

    public boolean isInternal(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        String key = clerkUserId.trim();
        Boolean cached = membershipCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        boolean active = opsInternalUserRepository.existsActiveInternal(key);
        membershipCache.put(key, active);
        return active;
    }

    public void invalidateAll() {
        activeIdsCache.invalidateAll();
        membershipCache.invalidateAll();
    }
}
