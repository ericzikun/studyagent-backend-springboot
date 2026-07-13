package com.studyagent.service.application.verla;

import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

@Configuration
public class VerlaContextCacheRepositoryConfig {

    @Bean
    @Primary
    public VerlaSessionRepository cacheAwareSessionRepository(
            @Qualifier("verlaSessionRepositoryImpl") VerlaSessionRepository delegate,
            ApplicationEventPublisher publisher) {
        return new VerlaSessionRepository() {
            @Override
            public VerlaSession save(VerlaSession session) {
                VerlaSession saved = delegate.save(session);
                if (saved != null && saved.getId() != null) {
                    publisher.publishEvent(new VerlaSessionCacheSyncEvent(saved.getId()));
                }
                return saved;
            }

            @Override
            public VerlaSession findById(Long id) {
                return delegate.findById(id);
            }

            @Override
            public VerlaSession findByIdForUpdate(Long id) {
                return delegate.findByIdForUpdate(id);
            }

            @Override
            public List<VerlaSession> findByTurn(Long turnId) {
                return delegate.findByTurn(turnId);
            }

            @Override
            public List<VerlaSession> findByIds(List<Long> sessionIds) {
                return delegate.findByIds(sessionIds);
            }

            @Override
            public Map<Long, List<VerlaSession>> findByTurnIds(List<Long> turnIds) {
                return delegate.findByTurnIds(turnIds);
            }

            @Override
            public List<VerlaSession> findCompletedSiblings(Long turnId, Long excludeSessionId) {
                return delegate.findCompletedSiblings(turnId, excludeSessionId);
            }

            @Override
            public VerlaSession findByCorrelationId(String correlationId) {
                return delegate.findByCorrelationId(correlationId);
            }

            @Override
            public boolean bindQuotaLedger(Long sessionId, Long ledgerId, Long amount) {
                boolean ok = delegate.bindQuotaLedger(sessionId, ledgerId, amount);
                if (ok && sessionId != null) {
                    publisher.publishEvent(new VerlaSessionCacheSyncEvent(sessionId));
                }
                return ok;
            }

            @Override
            public int countActiveAssignmentRuns() {
                return delegate.countActiveAssignmentRuns();
            }

            @Override
            public int countActiveCapabilityRuns(String action) {
                return delegate.countActiveCapabilityRuns(action);
            }
        };
    }

    @Bean
    @Primary
    public VerlaTurnRepository cacheAwareTurnRepository(
            @Qualifier("verlaTurnRepositoryImpl") VerlaTurnRepository delegate,
            ApplicationEventPublisher publisher) {
        return new VerlaTurnRepository() {
            @Override
            public VerlaTurn save(VerlaTurn turn) {
                VerlaTurn saved = delegate.save(turn);
                if (saved != null && saved.getId() != null) {
                    publisher.publishEvent(new VerlaTurnCacheSyncEvent(saved.getId()));
                }
                return saved;
            }

            @Override
            public VerlaTurn findById(Long id) {
                return delegate.findById(id);
            }

            @Override
            public VerlaTurn findByIdForUpdate(Long id) {
                return delegate.findByIdForUpdate(id);
            }

            @Override
            public List<VerlaTurn> findRecentByConversation(Long conversationId, int limit) {
                return delegate.findRecentByConversation(conversationId, limit);
            }

            @Override
            public List<VerlaTurn> findByIds(List<Long> turnIds) {
                return delegate.findByIds(turnIds);
            }

            @Override
            public Map<Long, List<VerlaTurn>> findRecentByConversationIds(List<Long> conversationIds) {
                return delegate.findRecentByConversationIds(conversationIds);
            }
        };
    }

    @Bean
    @Primary
    public VerlaConversationRepository cacheAwareConversationRepository(
            @Qualifier("verlaConversationRepositoryImpl") VerlaConversationRepository delegate,
            ApplicationEventPublisher publisher) {
        return new VerlaConversationRepository() {
            @Override
            public VerlaConversation save(VerlaConversation conversation) {
                VerlaConversation saved = delegate.save(conversation);
                if (saved != null && saved.getId() != null) {
                    publisher.publishEvent(new VerlaConversationCacheSyncEvent(saved.getId()));
                }
                return saved;
            }

            @Override
            public VerlaConversation findById(Long id) {
                return delegate.findById(id);
            }

            @Override
            public List<VerlaConversation> findByUserFilteredPaged(String userId,
                                                                   String segmentQueryKey,
                                                                   String conversationStatusDb,
                                                                   int page,
                                                                   int size) {
                return delegate.findByUserFilteredPaged(userId, segmentQueryKey, conversationStatusDb, page, size);
            }

            @Override
            public long countByUserFiltered(String userId, String segmentQueryKey, String conversationStatusDb) {
                return delegate.countByUserFiltered(userId, segmentQueryKey, conversationStatusDb);
            }

            @Override
            public List<VerlaConversation> searchByUserKeywordPaged(String userId,
                                                                    String keywordPattern,
                                                                    String segmentQueryKey,
                                                                    String conversationStatusDb,
                                                                    int page,
                                                                    int size) {
                return delegate.searchByUserKeywordPaged(
                        userId, keywordPattern, segmentQueryKey, conversationStatusDb, page, size);
            }

            @Override
            public long countByUserKeyword(String userId,
                                           String keywordPattern,
                                           String segmentQueryKey,
                                           String conversationStatusDb) {
                return delegate.countByUserKeyword(userId, keywordPattern, segmentQueryKey, conversationStatusDb);
            }

            @Override
            public List<VerlaConversation> findAdminFilteredPaged(String ownerUserId,
                                                                  String segmentQueryKey,
                                                                  String conversationStatusDb,
                                                                  boolean excludeInternal,
                                                                  int page,
                                                                  int size) {
                return delegate.findAdminFilteredPaged(
                        ownerUserId, segmentQueryKey, conversationStatusDb, excludeInternal, page, size);
            }

            @Override
            public long countAdminFiltered(String ownerUserId,
                                           String segmentQueryKey,
                                           String conversationStatusDb,
                                           boolean excludeInternal) {
                return delegate.countAdminFiltered(
                        ownerUserId, segmentQueryKey, conversationStatusDb, excludeInternal);
            }

            @Override
            public List<VerlaConversation> searchAdminKeywordPaged(String ownerUserId,
                                                                   String keywordPattern,
                                                                   String segmentQueryKey,
                                                                   String conversationStatusDb,
                                                                   boolean excludeInternal,
                                                                   int page,
                                                                   int size) {
                return delegate.searchAdminKeywordPaged(
                        ownerUserId, keywordPattern, segmentQueryKey, conversationStatusDb,
                        excludeInternal, page, size);
            }

            @Override
            public long countAdminKeyword(String ownerUserId,
                                          String keywordPattern,
                                          String segmentQueryKey,
                                          String conversationStatusDb,
                                          boolean excludeInternal) {
                return delegate.countAdminKeyword(
                        ownerUserId, keywordPattern, segmentQueryKey, conversationStatusDb, excludeInternal);
            }

            @Override
            public int touchOnNewTurn(Long id, Long turnId) {
                int updated = delegate.touchOnNewTurn(id, turnId);
                if (updated > 0 && id != null) {
                    publisher.publishEvent(new VerlaConversationCacheSyncEvent(id));
                }
                return updated;
            }

            @Override
            public int incrementVersion(Long id) {
                int updated = delegate.incrementVersion(id);
                if (updated > 0 && id != null) {
                    publisher.publishEvent(new VerlaConversationCacheSyncEvent(id));
                }
                return updated;
            }

            @Override
            public int updateTitle(Long id, String title) {
                return delegate.updateTitle(id, title);
            }

            @Override
            public int touchActiveAt(Long id) {
                // 仅刷新排序用的改动时间，不影响会话上下文内容，故不发缓存同步事件，避免点击/编辑造成缓存抖动
                return delegate.touchActiveAt(id);
            }
        };
    }
}
