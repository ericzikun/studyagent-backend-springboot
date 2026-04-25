package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaMessageEntity;
import com.studyagent.infra.mapper.verla.VerlaMessageMapper;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaMessageRepositoryImpl
        extends ServiceImpl<VerlaMessageMapper, VerlaMessageEntity>
        implements VerlaMessageRepository {

    @Override
    public VerlaMessage save(VerlaMessage message) {
        VerlaMessageEntity entity = toEntity(message);
        this.saveOrUpdate(entity);
        message.setId(entity.getId());
        return toDomain(entity);
    }

    @Override
    public VerlaMessage findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public List<VerlaMessage> findByCursor(Long conversationId, Long cursor, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return this.baseMapper.selectByCursor(conversationId, cursor, safeLimit)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private VerlaMessage toDomain(VerlaMessageEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaMessage.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .role(e.getRole())
                .sourceSessionId(e.getSourceSessionId())
                .textContent(e.getTextContent())
                .blocksJson(e.getBlocksJson())
                .attachmentsJson(e.getAttachmentsJson())
                .metaJson(e.getMetaJson())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private VerlaMessageEntity toEntity(VerlaMessage d) {
        if (d == null) {
            return null;
        }
        return new VerlaMessageEntity()
                .setId(d.getId())
                .setConversationId(d.getConversationId())
                .setTurnId(d.getTurnId())
                .setRole(d.getRole())
                .setSourceSessionId(d.getSourceSessionId())
                .setTextContent(d.getTextContent())
                .setBlocksJson(d.getBlocksJson())
                .setAttachmentsJson(d.getAttachmentsJson())
                .setMetaJson(d.getMetaJson())
                .setCreatedAt(d.getCreatedAt());
    }
}
