package com.studyagent.infra.client.clerk;

import com.studyagent.service.domain.user.ClerkClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Token 验证结果缓存
 * 使用内存缓存来避免重复的 Clerk API 调用
 * 与 Python 后端的 token_cache.py 保持一致
 */
@Slf4j
public class TokenCache {
    
    /**
     * 缓存条目：包含用户信息和时间戳
     */
    private static class CacheEntry {
        final ClerkClient.UserInfo userInfo;
        final long timestamp;
        
        CacheEntry(ClerkClient.UserInfo userInfo, long timestamp) {
            this.userInfo = userInfo;
            this.timestamp = timestamp;
        }
    }
    
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final long ttlMillis;
    private volatile long lastCleanup = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL = 60_000; // 60秒清理一次
    
    /**
     * 默认 TTL 为 5 分钟（300秒）
     */
    public TokenCache() {
        this(300_000); // 5分钟
    }
    
    /**
     * @param ttlMillis 缓存过期时间（毫秒）
     */
    public TokenCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }
    
    /**
     * 生成缓存 key
     * 使用 token 的 hash 值作为 key（避免存储完整 token）
     * 性能优化：使用更可靠的 hash 算法，避免 hash 冲突
     */
    private String getCacheKey(String token) {
        if (token == null || token.isEmpty()) {
            return "token:null";
        }
        // 使用 token 的前32个字符的 hash，更可靠且性能好
        // 对于 JWT token，前32个字符通常包含足够的信息来区分不同的 token
        String tokenPrefix = token.length() > 32 ? token.substring(0, 32) : token;
        return "token:" + tokenPrefix.hashCode() + ":" + token.length();
    }
    
    /**
     * 检查缓存条目是否过期
     */
    private boolean isExpired(long timestamp) {
        return System.currentTimeMillis() - timestamp > ttlMillis;
    }
    
    /**
     * 清理过期的缓存条目
     */
    private void cleanupExpired() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanup < CLEANUP_INTERVAL) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            int removedCount = 0;
            var iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (isExpired(entry.getValue().timestamp)) {
                    iterator.remove();
                    removedCount++;
                }
            }
            
            if (removedCount > 0) {
                log.debug("清理了 {} 个过期的 token 缓存条目", removedCount);
            }
            lastCleanup = currentTime;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 从缓存获取 token 验证结果
     */
    public ClerkClient.UserInfo get(String token) {
        String cacheKey = getCacheKey(token);
        
        lock.readLock().lock();
        try {
            // 定期清理过期条目
            if (System.currentTimeMillis() - lastCleanup >= CLEANUP_INTERVAL) {
                lock.readLock().unlock();
                cleanupExpired();
                lock.readLock().lock();
            }
            
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null) {
                if (!isExpired(entry.timestamp)) {
                    log.debug("Token 缓存命中: {}...", cacheKey.substring(0, Math.min(20, cacheKey.length())));
                    return entry.userInfo;
                } else {
                    // 已过期，删除
                    lock.readLock().unlock();
                    lock.writeLock().lock();
                    try {
                        cache.remove(cacheKey);
                        log.debug("Token 缓存已过期: {}...", cacheKey.substring(0, Math.min(20, cacheKey.length())));
                    } finally {
                        lock.writeLock().unlock();
                        lock.readLock().lock();
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        
        return null;
    }
    
    /**
     * 将 token 验证结果存入缓存
     */
    public void set(String token, ClerkClient.UserInfo userInfo) {
        String cacheKey = getCacheKey(token);
        long timestamp = System.currentTimeMillis();
        
        lock.writeLock().lock();
        try {
            cache.put(cacheKey, new CacheEntry(userInfo, timestamp));
            log.debug("Token 缓存已更新: {}... (TTL: {}ms)", 
                cacheKey.substring(0, Math.min(20, cacheKey.length())), ttlMillis);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 使指定 token 的缓存失效
     */
    public void invalidate(String token) {
        String cacheKey = getCacheKey(token);
        
        lock.writeLock().lock();
        try {
            if (cache.remove(cacheKey) != null) {
                log.debug("Token 缓存已清除: {}...", cacheKey.substring(0, Math.min(20, cacheKey.length())));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 清空所有缓存
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            int size = cache.size();
            cache.clear();
            log.debug("已清空所有 token 缓存 ({} 个条目)", size);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取缓存大小
     */
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}

