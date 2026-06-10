package com.studyagent.api.dto.verla.response;

import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
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

    private String messageId;
    private String turnId;
    private String role;
    private String sourceSessionId;
    private String text;
    /** 用户消息附件数组的原始 JSON 字符串（objectId / filename / mime 等） */
    private String attachmentsJson;
    /** 卡片/Block 数组的原始 JSON 字符串（前端按 §22.2 解析） */
    private String blocksJson;
    /** 扩展元数据，如 {"thinking": "..."} */
    private String metaJson;
    private LocalDateTime createdAt;

    public static VerlaMessageVO from(VerlaMessage m) {
        return from(m, true);
    }

    public static VerlaMessageVO fromInternal(VerlaMessage m) {
        return from(m, false);
    }

    private static VerlaMessageVO from(VerlaMessage m, boolean encodePublicIds) {
        return VerlaMessageVO.builder()
                .messageId(VerlaPublicIdVoSupport.message(m.getId(), encodePublicIds))
                .turnId(VerlaPublicIdVoSupport.turn(m.getTurnId(), encodePublicIds))
                .role(m.getRole())
                .sourceSessionId(VerlaPublicIdVoSupport.session(m.getSourceSessionId(), encodePublicIds))
                .text(m.getTextContent())
                .attachmentsJson(m.getAttachmentsJson())
                .blocksJson(VerlaBlocksJsonSanitizer.withoutTopLevelStage(m.getBlocksJson()))
                .metaJson(m.getMetaJson())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
