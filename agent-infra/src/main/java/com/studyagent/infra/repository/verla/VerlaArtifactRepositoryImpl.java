package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaArtifactRepositoryImpl
        extends ServiceImpl<VerlaArtifactMapper, VerlaArtifactEntity>
        implements VerlaArtifactRepository {

    @Override
    public VerlaArtifact findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public List<VerlaArtifact> findByConversation(Long conversationId) {
        return this.baseMapper.selectByConversation(conversationId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaArtifact> findBySession(Long sessionId) {
        return this.baseMapper.selectBySession(sessionId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private VerlaArtifact toDomain(VerlaArtifactEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaArtifact.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .kind(e.getKind())
                .mime(e.getMime())
                .bodyOrRef(e.getBodyOrRef())
                .version(e.getVersion())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
