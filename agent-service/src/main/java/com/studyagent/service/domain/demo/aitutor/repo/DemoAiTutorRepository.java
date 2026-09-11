package com.studyagent.service.domain.demo.aitutor.repo;

import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocVersion;
import com.studyagent.service.domain.demo.aitutor.AiTutorEvidence;
import com.studyagent.service.domain.demo.aitutor.AiTutorMessage;

import java.util.List;
import java.util.Optional;

/** AI Tutor demo 数据仓库端口（实现位于 agent-infra，保持 service 不依赖 infra） */
public interface DemoAiTutorRepository {

    AiTutorConversation saveConversation(AiTutorConversation c);

    List<AiTutorConversation> listConversations(String clerkUserId, int limit);

    Optional<AiTutorConversation> getOwnedConversation(String clerkUserId, Long conversationId);

    /** 由主线 {@code verla_conversations.id} 反查 demo 会话（uk_aitutor_verla_conv 唯一键） */
    Optional<AiTutorConversation> findByVerlaConversationId(Long verlaConversationId);

    /** 取用户最近一个既无消息也无文档的会话，用于「进入页面即分配会话」的草稿复用 */
    Optional<AiTutorConversation> findLatestUnusedConversation(String clerkUserId);

    void touchConversationUpdatedAt(Long conversationId);

    AiTutorMessage appendMessage(AiTutorMessage m);

    List<AiTutorMessage> listMessages(Long conversationId);

    AiTutorEvidence addEvidence(AiTutorEvidence e);

    AiTutorEvidence updateEvidence(AiTutorEvidence e);

    List<AiTutorEvidence> listEvidences(Long conversationId);

    Optional<AiTutorEvidence> getEvidence(Long evidenceId);

    Optional<AiTutorDocument> getDocument(Long conversationId);

    /** 保存文档并追加一个版本快照（同一事务） */
    AiTutorDocument saveDocumentWithVersion(AiTutorDocument doc, AiTutorDocVersion version);

    AiTutorDocVersion getDocumentVersion(Long documentId, Long versionNo);

    List<AiTutorDocVersion> listDocumentVersions(Long documentId);

    AiTutorDocument applyVersion(Long conversationId, Long versionNo);
}
