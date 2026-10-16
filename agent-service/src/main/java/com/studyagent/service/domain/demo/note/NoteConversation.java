package com.studyagent.service.domain.demo.note;

import lombok.Data;

import java.time.LocalDateTime;

/** 笔记会话（demo_note_conversation）。 */
@Data
public class NoteConversation {

    private Long id;
    private String clerkUserId;

    /**
     * 主线 {@code verla_conversations.id}。
     * <p>demo 会话建表时同事务创建，作为 SSE 事件通道
     * （{@code /v1/verla/conversations/{cid}/events}）与事件归属校验的键。
     */
    private Long verlaConversationId;

    private String title;
    /** 素材类型：file / text；提交前为 null。 */
    private String sourceType;
    /** 上传文件的附件 objectId（sourceType=file 时有值）。 */
    private String attachmentObjectId;
    /** 粘贴的原始文本（sourceType=text 时有值）。 */
    private String inputText;
    /** 表单：课程名称（可为空）。 */
    private String courseName;
    /** 表单：学习目标枚举码（exam_prep / quiz_review / homework / understand_lesson / take_notes / generate_questions / flashcards / other）。 */
    private String learningGoal;
    /** 生成出的笔记 Markdown。 */
    private String noteMd;
    /**
     * 笔记版本号：AI 生成或用户手改各 +1。
     * <p>用户保存带 baseVersion 做乐观锁，与 AI Tutor 的双向基线同语义。
     */
    private Long noteVersion;
    /**
     * 待用户接受/忽略的章节级改动提案（JSON 原文）。
     * <p>后续轮 agent 改文档不再直接落库：提案先存这里等用户在编辑器里逐块决策，
     * 提交时才写 {@link #noteMd} 并推进版本。NULL = 无待审提案。
     */
    private String pendingProposalJson;
    /** draft / generating / completed / failed。 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
