package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaConversationRepositoryImpl
        extends ServiceImpl<VerlaConversationMapper, VerlaConversationEntity>
        implements VerlaConversationRepository {

    @Override
    public VerlaConversation save(VerlaConversation conversation) {
        VerlaConversationEntity entity = toEntity(conversation);
        this.saveOrUpdate(entity);
        conversation.setId(entity.getId());
        return toDomain(entity);
    }

    @Override
    public VerlaConversation findById(Long id) {
        VerlaConversationEntity e = this.getById(id);
        return toDomain(e);
    }

    @Override
    public List<VerlaConversation> findByUserFilteredPaged(String userId,
                                                           String segmentQueryKey,
                                                           String conversationStatusDb,
                                                           int page,
                                                           int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return this.baseMapper.selectByUserFilteredPaged(
                        userId, segmentQueryKey, conversationStatusDb, s, (p - 1) * s)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public long countByUserFiltered(String userId,
                                    String segmentQueryKey,
                                    String conversationStatusDb) {
        return this.baseMapper.countByUserFiltered(userId, segmentQueryKey, conversationStatusDb);
    }

    @Override
    public List<VerlaConversation> searchByUserKeywordPaged(String userId,
                                                            String keywordPattern,
                                                            String segmentQueryKey,
                                                            String conversationStatusDb,
                                                            int page,
                                                            int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return this.baseMapper.searchByUserKeywordPaged(
                        userId, keywordPattern, segmentQueryKey, conversationStatusDb, s, (p - 1) * s)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public long countByUserKeyword(String userId,
                                   String keywordPattern,
                                   String segmentQueryKey,
                                   String conversationStatusDb) {
        return this.baseMapper.countByUserKeyword(
                userId, keywordPattern, segmentQueryKey, conversationStatusDb);
    }

    @Override
    public List<VerlaConversation> findAdminFilteredPaged(String ownerUserId,
                                                         String segmentQueryKey,
                                                         String conversationStatusDb,
                                                         int page,
                                                         int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return this.baseMapper.selectAdminFilteredPaged(
                        ownerUserId, segmentQueryKey, conversationStatusDb, s, (p - 1) * s)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public long countAdminFiltered(String ownerUserId,
                                   String segmentQueryKey,
                                   String conversationStatusDb) {
        return this.baseMapper.countAdminFiltered(ownerUserId, segmentQueryKey, conversationStatusDb);
    }

    @Override
    public List<VerlaConversation> searchAdminKeywordPaged(String ownerUserId,
                                                           String keywordPattern,
                                                           String segmentQueryKey,
                                                           String conversationStatusDb,
                                                           int page,
                                                           int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return this.baseMapper.searchAdminKeywordPaged(
                        ownerUserId, keywordPattern, segmentQueryKey, conversationStatusDb, s, (p - 1) * s)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public long countAdminKeyword(String ownerUserId,
                                  String keywordPattern,
                                  String segmentQueryKey,
                                  String conversationStatusDb) {
        return this.baseMapper.countAdminKeyword(
                ownerUserId, keywordPattern, segmentQueryKey, conversationStatusDb);
    }

    @Override
    public int touchOnNewTurn(Long id, Long turnId) {
        return this.baseMapper.touchOnNewTurn(id, turnId);
    }

    @Override
    public int incrementVersion(Long id) {
        return this.baseMapper.incrementVersion(id);
    }

    @Override
    public int touchActiveAt(Long id) {
        return this.baseMapper.touchActiveAt(id);
    }

    @Override
    public int updateTitle(Long id, String title) {
        return this.baseMapper.updateTitle(id, title);
    }

    private VerlaConversation toDomain(VerlaConversationEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaConversation.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .title(e.getTitle())
                .status(e.getStatus())
                .primaryIntent(e.getPrimaryIntent())
                .intentLifecycle(e.getIntentLifecycle())
                .workspaceJson(e.getWorkspaceJson())
                .turnCount(e.getTurnCount())
                .lastTurnId(e.getLastTurnId())
                .lastMessageAt(e.getLastMessageAt())
                .lastActiveAt(e.getLastActiveAt())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private VerlaConversationEntity toEntity(VerlaConversation d) {
        if (d == null) {
            return null;
        }
        return new VerlaConversationEntity()
                .setId(d.getId())
                .setUserId(d.getUserId())
                .setTitle(d.getTitle())
                .setStatus(d.getStatus())
                .setPrimaryIntent(d.getPrimaryIntent())
                .setIntentLifecycle(d.getIntentLifecycle())
                .setWorkspaceJson(d.getWorkspaceJson())
                .setTurnCount(d.getTurnCount())
                .setLastTurnId(d.getLastTurnId())
                .setLastMessageAt(d.getLastMessageAt())
                .setLastActiveAt(d.getLastActiveAt())
                .setVersion(d.getVersion())
                .setCreatedAt(d.getCreatedAt())
                .setUpdatedAt(d.getUpdatedAt());
    }
}
