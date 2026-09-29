package com.studyagent.service.domain.demo.note.repo;

import com.studyagent.service.domain.demo.note.NoteConversation;

import java.util.Optional;

/** 笔记 demo 数据仓库端口（实现位于 agent-infra，保持 service 不依赖 infra）。 */
public interface DemoNoteRepository {

    NoteConversation saveConversation(NoteConversation c);

    Optional<NoteConversation> getOwnedConversation(String clerkUserId, Long conversationId);

    /** 由主线 {@code verla_conversations.id} 反查 demo 会话（uk_note_verla_conv 唯一键）。 */
    Optional<NoteConversation> findByVerlaConversationId(Long verlaConversationId);

    /** 取用户最近一个尚未提交素材的会话，用于「进入页面即分配会话」的草稿复用。 */
    Optional<NoteConversation> findLatestDraft(String clerkUserId);
}
