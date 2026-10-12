package com.studyagent.service.domain.demo.note.repo;

import com.studyagent.service.domain.demo.note.NoteConversation;
import com.studyagent.service.domain.demo.note.NoteMessage;

import java.util.List;
import java.util.Optional;

/** 笔记 demo 数据仓库端口（实现位于 agent-infra，保持 service 不依赖 infra）。 */
public interface DemoNoteRepository {

    NoteConversation saveConversation(NoteConversation c);

    /** 追加一条回合消息；seq 由实现层取「会话内最大值 +1」。 */
    NoteMessage appendMessage(NoteMessage message);

    /**
     * 取会话内**最近** {@code limit} 条消息，按 seq **升序**返回（旧→新）。
     * <p>快照取足够大的 limit 拿全量；history 裁剪取小 limit 再用字节预算从尾部往前裁。
     */
    List<NoteMessage> listRecentMessages(Long conversationId, int limit);

    /** 清空会话消息（首轮重新提交素材时调用：旧对话上下文随即作废）。 */
    void deleteMessages(Long conversationId);

    Optional<NoteConversation> getOwnedConversation(String clerkUserId, Long conversationId);

    /** 由主线 {@code verla_conversations.id} 反查 demo 会话（uk_note_verla_conv 唯一键）。 */
    Optional<NoteConversation> findByVerlaConversationId(Long verlaConversationId);

    /** 取用户最近一个尚未提交素材的会话，用于「进入页面即分配会话」的草稿复用。 */
    Optional<NoteConversation> findLatestDraft(String clerkUserId);

    /**
     * 用户的历史笔记列表（按最近活动倒序）。
     * <p>排除尚未提交素材的 {@code draft}：历史栏展示的是「上传/处理过」的记录。
     */
    List<NoteConversation> listProcessedConversations(String clerkUserId, int limit);
}
