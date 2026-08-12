package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.AiWritingHumanizerResultEntity;
import com.studyagent.infra.mapper.verla.AiWritingHumanizerResultMapper;
import com.studyagent.service.domain.verla.AiWritingHumanizerResult;
import com.studyagent.service.domain.verla.repo.AiWritingHumanizerResultRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class AiWritingHumanizerResultRepositoryImpl
        extends ServiceImpl<AiWritingHumanizerResultMapper, AiWritingHumanizerResultEntity>
        implements AiWritingHumanizerResultRepository {

    @Override
    public void insertIgnoreByArtifactUid(AiWritingHumanizerResult row) {
        if (row == null || row.getArtifactUid() == null || row.getArtifactUid().isBlank()) {
            return;
        }
        if (row.getClerkUserId() == null || row.getClerkUserId().isBlank()) {
            return;
        }
        if (row.getResultText() == null || row.getResultText().isBlank()) {
            return;
        }
        if (row.getResultHash() == null || row.getResultHash().isBlank()) {
            return;
        }
        AiWritingHumanizerResultEntity entity = new AiWritingHumanizerResultEntity()
                .setClerkUserId(row.getClerkUserId())
                .setConversationId(row.getConversationId())
                .setSessionId(row.getSessionId())
                .setArtifactUid(row.getArtifactUid())
                .setResultHash(row.getResultHash())
                .setResultText(row.getResultText())
                .setCreatedAt(row.getCreatedAt() != null ? row.getCreatedAt() : LocalDateTime.now());
        this.baseMapper.insertIgnore(entity);
    }

    @Override
    public boolean existsByUserAndHash(String clerkUserId, String resultHash) {
        if (clerkUserId == null || clerkUserId.isBlank() || resultHash == null || resultHash.isBlank()) {
            return false;
        }
        return this.baseMapper.existsByUserAndHash(clerkUserId, resultHash) != null;
    }

    @Override
    public List<AiWritingHumanizerResult> listRecentByUser(String clerkUserId, int limit) {
        if (clerkUserId == null || clerkUserId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        return this.baseMapper.selectRecentByUser(clerkUserId, limit).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    private AiWritingHumanizerResult toDomain(AiWritingHumanizerResultEntity e) {
        if (e == null) {
            return null;
        }
        return AiWritingHumanizerResult.builder()
                .id(e.getId())
                .clerkUserId(e.getClerkUserId())
                .conversationId(e.getConversationId())
                .sessionId(e.getSessionId())
                .artifactUid(e.getArtifactUid())
                .resultHash(e.getResultHash())
                .resultText(e.getResultText())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
