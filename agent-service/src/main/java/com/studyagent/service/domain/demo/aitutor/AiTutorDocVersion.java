package com.studyagent.service.domain.demo.aitutor;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tutor 文档版本快照（demo_ai_tutor_doc_version） */
@Data
public class AiTutorDocVersion {
    private Long id;
    private Long documentId;
    private Long versionNo;
    private String source;     // ai/user
    private String contentMd;
    private LocalDateTime createdAt;
}
