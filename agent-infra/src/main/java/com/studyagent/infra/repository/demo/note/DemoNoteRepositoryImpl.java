package com.studyagent.infra.repository.demo.note;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.demo.note.DemoNoteConversationEntity;
import com.studyagent.infra.entity.demo.note.DemoNoteMessageEntity;
import com.studyagent.infra.mapper.demo.note.DemoNoteConversationMapper;
import com.studyagent.infra.mapper.demo.note.DemoNoteMessageMapper;
import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;
import com.studyagent.service.domain.demo.note.repo.DemoNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 笔记 demo 仓库实现：MP 实体 <-> 领域对象互转。 */
@Repository
@RequiredArgsConstructor
public class DemoNoteRepositoryImpl implements DemoNoteRepository {

    private final DemoNoteConversationMapper convMapper;
    private final DemoNoteMessageMapper msgMapper;

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
    public NoteMessage appendMessage(NoteMessage message) {
        DemoNoteMessageEntity e = toEntity(message);
        Integer nextSeq = nextSeq(message.getConversationId());
        e.setSeq(nextSeq);
        e.setCreatedAt(LocalDateTime.now());
        msgMapper.insert(e);
        message.setId(e.getId());
        message.setSeq(nextSeq);
        message.setCreatedAt(e.getCreatedAt());
        return message;
    }

    @Override
    public List<NoteMessage> listRecentMessages(Long conversationId, int limit) {
        List<DemoNoteMessageEntity> rows = msgMapper.selectList(
                new LambdaQueryWrapper<DemoNoteMessageEntity>()
                        .eq(DemoNoteMessageEntity::getConversationId, conversationId)
                        .orderByDesc(DemoNoteMessageEntity::getSeq)
                        .last("LIMIT " + Math.max(limit, 1)));
        // 查询取的是最近 N 条（新→旧）；返回前翻成旧→新，调用方的字节预算从尾部往前裁才成立
        List<NoteMessage> messages = new ArrayList<>(rows.size());
        for (DemoNoteMessageEntity row : rows) {
            messages.add(toDomain(row));
        }
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public void deleteMessages(Long conversationId) {
        msgMapper.delete(new LambdaQueryWrapper<DemoNoteMessageEntity>()
                .eq(DemoNoteMessageEntity::getConversationId, conversationId));
    }

    /** 会话内下一个 seq（当前最大值 +1）；无消息时从 1 开始。 */
    private Integer nextSeq(Long conversationId) {
        DemoNoteMessageEntity last = msgMapper.selectOne(
                new LambdaQueryWrapper<DemoNoteMessageEntity>()
                        .eq(DemoNoteMessageEntity::getConversationId, conversationId)
                        .orderByDesc(DemoNoteMessageEntity::getSeq)
                        .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 1 : last.getSeq() + 1;
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
        e.setCourseName(c.getCourseName());
        e.setLearningGoal(c.getLearningGoal());
        e.setNoteMd(c.getNoteMd());
        e.setNoteVersion(c.getNoteVersion());
        e.setPendingProposalJson(c.getPendingProposalJson());
        e.setStatus(c.getStatus());
        e.setCreatedAt(c.getCreatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }

    private DemoNoteMessageEntity toEntity(NoteMessage m) {
        DemoNoteMessageEntity e = new DemoNoteMessageEntity();
        e.setId(m.getId());
        e.setConversationId(m.getConversationId());
        e.setRole(m.getRole());
        e.setMsgType(m.getMsgType());
        e.setContentMd(m.getContentMd());
        e.setSeq(m.getSeq());
        e.setCreatedAt(m.getCreatedAt());
        return e;
    }

    private NoteMessage toDomain(DemoNoteMessageEntity e) {
        NoteMessage m = new NoteMessage();
        m.setId(e.getId());
        m.setConversationId(e.getConversationId());
        m.setRole(e.getRole());
        m.setMsgType(e.getMsgType());
        m.setContentMd(e.getContentMd());
        m.setSeq(e.getSeq());
        m.setCreatedAt(e.getCreatedAt());
        return m;
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
        c.setCourseName(e.getCourseName());
        c.setLearningGoal(e.getLearningGoal());
        c.setNoteMd(e.getNoteMd());
        c.setNoteVersion(e.getNoteVersion());
        c.setPendingProposalJson(e.getPendingProposalJson());
        c.setStatus(e.getStatus());
        c.setCreatedAt(e.getCreatedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }
}
