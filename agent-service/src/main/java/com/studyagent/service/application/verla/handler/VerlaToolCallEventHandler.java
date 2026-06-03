package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaToolStatus;
import com.studyagent.common.verla.enums.VerlaToolVisibility;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaToolCallPayload;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verla tool call trace 写路径 handler（PR-V2-02）。
 * <p>
 * 处理 {@link VerlaAgentEventType#AGENT_TOOL_CALL_RECORDED}：
 * <ul>
 *   <li>按 {@code tool_call_id} 幂等 upsert（终态不可回退、非空字段才覆盖）；</li>
 *   <li>对 toolInput / toolOutput / meta 做最小脱敏（>16KB 截断 + 标 truncated）；</li>
 *   <li>仅当 visibility = USER_VISIBLE 时 bump conv version，避免 INTERNAL trace 频繁
 *       让上下文缓存失效，详见 docs/V2/5.1 §1 / §8。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaToolCallEventHandler implements VerlaEventHandler {

    /** 单字段最大 JSON 序列化长度，超过截断 */
    private static final int FIELD_MAX_LEN = 16 * 1024;
    private static final String TRUNCATED_TAIL = "...[truncated]";

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.AGENT_TOOL_CALL_RECORDED);

    private final VerlaToolCallRepository toolCallRepository;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaToolCallPayload p = parsePayload(env);
        if (p == null) {
            log.warn("[Verla/tool] empty payload, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        if (p.getToolCallId() == null || p.getToolCallId().isBlank()) {
            log.warn("[Verla/tool] AGENT_TOOL_CALL_RECORDED missing tool_call_id, sessionId={} seq={} tool={}",
                    row.getSessionId(), row.getEventSeq(), p.getToolName());
            return;
        }

        String visibility = p.getVisibility() != null
                ? p.getVisibility() : VerlaToolVisibility.INTERNAL.name();
        String status = p.getStatus() != null ? p.getStatus() : VerlaToolStatus.PENDING.name();

        VerlaToolCall patch = VerlaToolCall.builder()
                .toolCallId(p.getToolCallId())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .stepId(row.getStepId())
                .nodeId(p.getNodeId())
                .parentCallId(p.getParentCallId())
                .agentName(p.getAgentName())
                .toolName(p.getToolName())
                .status(status)
                .visibility(visibility)
                .toolInputJson(toTruncatedJson(p.getToolInput()))
                .toolOutputJson(toTruncatedJson(p.getToolOutput()))
                .summary(truncateString(p.getSummary(), 1024))
                .errorCode(truncateString(p.getErrorCode(), 64))
                .errorMessage(truncateString(p.getErrorMessage(), 1024))
                .startedAt(toLocalDateTime(p.getStartedAt()))
                .finishedAt(toLocalDateTime(p.getFinishedAt()))
                .durationMs(p.getDurationMs())
                .metaJson(toTruncatedJson(p.getMeta()))
                .build();

        VerlaToolCall saved = toolCallRepository.upsertByCallId(patch);
        log.info("[Verla/tool] upsert ok cid={} callId={} agent={} tool={} status={} visibility={}",
                saved.getConversationId(), saved.getToolCallId(), saved.getAgentName(),
                saved.getToolName(), saved.getStatus(), saved.getVisibility());

        // V2 缓存失效策略：USER_VISIBLE 才 bump，INTERNAL 不影响 hydrate 上下文
        if (VerlaToolVisibility.USER_VISIBLE.name().equalsIgnoreCase(saved.getVisibility())) {
            conversationRepository.incrementVersion(row.getConversationId());
        }
    }

    private VerlaToolCallPayload parsePayload(VerlaEventEnvelope env) {
        if (env == null || env.getPayload() == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(env.getPayload(), VerlaToolCallPayload.class);
        } catch (Exception e) {
            log.warn("[Verla/tool] payload convert failed: {}", e.getMessage());
            return null;
        }
    }

    private String toTruncatedJson(Object obj) {
        if (obj == null) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/tool] field serialize failed: {}", e.getMessage());
            return null;
        }
        if (json.length() <= FIELD_MAX_LEN) {
            return json;
        }
        return toTruncatedJsonEnvelope(json);
    }

    private String toTruncatedJsonEnvelope(String json) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_truncated", true);
        envelope.put("originalJsonLength", json.length());
        envelope.put("preview", json.substring(0, FIELD_MAX_LEN / 2) + TRUNCATED_TAIL);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[Verla/tool] truncated field serialize failed: {}", e.getMessage());
            return "{\"_truncated\":true,\"preview\":\"[truncated]\"}";
        }
    }

    private static String truncateString(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime toLocalDateTime(java.time.Instant inst) {
        return inst == null ? null : LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
    }
}
