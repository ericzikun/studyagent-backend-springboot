package com.studyagent.api.dto.verla.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /v1/verla/v2/uploads/sign
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaUploadSignRequest {

    private Long conversationId;
    /** Internal agent output upload must carry the Java/Clerk user id explicitly. */
    private String clerkUserId;
    private String filename;
    private String mime;
    private Long sizeBytes;
    /** 可选：绑定 turn（发送消息前后均可） */
    private Long turnId;
    /** 预留：关联 verla_sessions.id */
    private Long sessionId;
    /** Internal upload source marker, e.g. USER_UPLOAD / AGENT_OUTPUT. */
    private String attachmentOrigin;
    /** Optional attachment metadata JSON supplied by the caller. */
    private String metaJson;
}
