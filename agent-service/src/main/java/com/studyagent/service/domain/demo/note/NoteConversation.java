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
    /** 生成出的笔记 Markdown。 */
    private String noteMd;
    /** draft / generating / completed / failed。 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
