package com.studyagent.service.application.verla.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * SSE 推送给前端的 verla 事件载体
 * <p>
 * 字段对齐 docs/verla-Java侧MVP技术方案.md §13.4。\
 * <ul>
 *   <li>{@code id}：用 verla_event_inbox.id（conv 维度全局递增），即 SSE event id；</li>
 *   <li>{@code type}：原始 envelope.eventType；</li>
 *   <li>{@code conversationId/turnId/sessionId}：路由到前端 UI 节点；</li>
 *   <li>{@code stepId/stepSeq}：流式增量定位；</li>
 *   <li>{@code payload}：原 envelope.payload 透传，前端按 type 自解析。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerlaSseEventPayload {

    private Long id;
    private String type;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String stepId;
    private Integer stepSeq;
    private Map<String, Object> payload;
}
