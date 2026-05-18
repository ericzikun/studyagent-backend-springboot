package com.studyagent.common.verla.envelope.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code AGENT_CLARIFY_FORM_ISSUED} 事件 payload。
 * <p>
 * Agent 在需要追问时通过该事件下发动态问卷，Java 侧 upsert {@code verla_clarify_forms}
 * 并附带在 message blocks 中给前端渲染。详见 docs/V2/5.1 §4.2。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerlaClarifyFormPayload {

    /** 业务唯一 ID（form_*） */
    private String formId;

    /** 关联的 assistant message id（可空：Java handler 写完再回填） */
    private Long messageId;

    private String title;
    private String description;

    /** 动态字段定义；通常每项含 key / label / type / options / required ... */
    private List<Map<String, Object>> schema;

    /** 可空过期时间 */
    private Instant expiresAt;

    /** 透传字段（如 prefill / hints），handler 可保留到 meta_json */
    private Map<String, Object> meta;
}
