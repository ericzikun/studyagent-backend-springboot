package com.studyagent.service.domain.demo.note;

import lombok.Data;

import java.time.LocalDateTime;

/** 笔记回合的一条消息（demo_note_message）。 */
@Data
public class NoteMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String TYPE_TEXT = "text";

    private Long id;
    private Long conversationId;
    /** user / assistant。 */
    private String role;
    /** text（预留 artifact / system）。 */
    private String msgType;
    private String contentMd;
    /** 会话内递增序号，从 1 开始。 */
    private Integer seq;
    private LocalDateTime createdAt;
}
