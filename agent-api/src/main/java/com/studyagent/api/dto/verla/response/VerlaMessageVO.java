package com.studyagent.api.dto.verla.response;

import com.studyagent.service.domain.verla.VerlaMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaMessageVO {

    private Long messageId;
    private Long turnId;
    private String role;
    private Long sourceSessionId;
    private String text;
    /** 用户消息附件数组的原始 JSON 字符串（objectId / filename / mime 等） */
    private String attachmentsJson;
    /** 卡片/Block 数组的原始 JSON 字符串（前端按 §22.2 解析） */
    private String blocksJson;
    private LocalDateTime createdAt;

    public static VerlaMessageVO from(VerlaMessage m) {
        return VerlaMessageVO.builder()
                .messageId(m.getId())
                .turnId(m.getTurnId())
                .role(m.getRole())
                .sourceSessionId(m.getSourceSessionId())
                .text(m.getTextContent())
                .attachmentsJson(m.getAttachmentsJson())
                .blocksJson(VerlaBlocksJsonSanitizer.withoutTopLevelStage(m.getBlocksJson()))
                .createdAt(m.getCreatedAt())
                .build();
    }
}
