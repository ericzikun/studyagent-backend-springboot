package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla 卡片 / 材料终稿领域对象
 * <p>
 * 对应 verla_artifacts 表，详见 docs/verla-Java侧MVP技术方案.md §4.6 / §13.4。\
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaArtifact {

    private Long id;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    /** assignment_card / flashcards / outline ... */
    private String kind;
    private String mime;
    /** 正文（小）或 OSS 引用（大） */
    private String bodyOrRef;
    /** 增量更新版本，初始 1 */
    private Integer version;
    private LocalDateTime updatedAt;
}
