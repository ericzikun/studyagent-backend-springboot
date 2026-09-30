package com.studyagent.infra.repository.demo.note;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.demo.note.DemoNoteConversationEntity;
import com.studyagent.infra.mapper.demo.note.DemoNoteConversationMapper;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 笔记 demo 仓库实现：MP 实体 <-> 领域对象互转。 */
@Repository
@RequiredArgsConstructor
public class DemoNoteRepositoryImpl implements DemoNoteRepository {

    private final DemoNoteConversationMapper convMapper;

    @Override
    public NoteConversation saveConversation(NoteConversation c) {
        DemoNoteConversationEntity e = toEntity(c);
        if (c.getId() == null) {
            convMapper.insert(e);
            c.setId(e.getId());
        } else {
            convMapper.updateById(e);
        }
        return c;
    }

    @Override
    public Optional<NoteConversation> getOwnedConversation(String clerkUserId, Long conversationId) {
        DemoNoteConversationEntity e = convMapper.selectOne(new LambdaQueryWrapper<DemoNoteConversationEntity>()
                .eq(DemoNoteConversationEntity::getId, conversationId)
                .eq(DemoNoteConversationEntity::getClerkUserId, clerkUserId));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Override
    public Optional<NoteConversation> findByVerlaConversationId(Long verlaConversationId) {
        if (verlaConversationId == null) {
            return Optional.empty();
        }
        DemoNoteConversationEntity e = convMapper.selectOne(new LambdaQueryWrapper<DemoNoteConversationEntity>()
                .eq(DemoNoteConversationEntity::getVerlaConversationId, verlaConversationId));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Override
    public Optional<NoteConversation> findLatestDraft(String clerkUserId) {
        DemoNoteConversationEntity e = convMapper.selectOne(new LambdaQueryWrapper<DemoNoteConversationEntity>()
                .eq(DemoNoteConversationEntity::getClerkUserId, clerkUserId)
                .eq(DemoNoteConversationEntity::getStatus, "draft")
                .orderByDesc(DemoNoteConversationEntity::getId)
                .last("LIMIT 1"));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Override
    public List<NoteConversation> listProcessedConversations(String clerkUserId, int limit) {
        return convMapper.selectList(new LambdaQueryWrapper<DemoNoteConversationEntity>()
                        .eq(DemoNoteConversationEntity::getClerkUserId, clerkUserId)
                        .ne(DemoNoteConversationEntity::getStatus, "draft")
                        .orderByDesc(DemoNoteConversationEntity::getUpdatedAt)
                        .last("LIMIT " + limit))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private DemoNoteConversationEntity toEntity(NoteConversation c) {
        DemoNoteConversationEntity e = new DemoNoteConversationEntity();
        e.setId(c.getId());
        e.setClerkUserId(c.getClerkUserId());
        e.setVerlaConversationId(c.getVerlaConversationId());
        e.setTitle(c.getTitle());
        e.setSourceType(c.getSourceType());
        e.setAttachmentObjectId(c.getAttachmentObjectId());
        e.setInputText(c.getInputText());
        e.setNoteMd(c.getNoteMd());
        e.setStatus(c.getStatus());
        e.setCreatedAt(c.getCreatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }

    private NoteConversation toDomain(DemoNoteConversationEntity e) {
        NoteConversation c = new NoteConversation();
        c.setId(e.getId());
        c.setClerkUserId(e.getClerkUserId());
        c.setVerlaConversationId(e.getVerlaConversationId());
        c.setTitle(e.getTitle());
        c.setSourceType(e.getSourceType());
        c.setAttachmentObjectId(e.getAttachmentObjectId());
        c.setInputText(e.getInputText());
        c.setNoteMd(e.getNoteMd());
        c.setStatus(e.getStatus());
        c.setCreatedAt(e.getCreatedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }
}
