package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaClarifyFormPayload;
import com.studyagent.service.domain.verla.VerlaClarifyForm;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaClarifyFormRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import com.studyagent.common.datetime.DateTimeFormats;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verla clarify form 写路径 handler（PR-V2-02）。
 * <p>
 * 处理 {@link VerlaAgentEventType#AGENT_CLARIFY_FORM_ISSUED}：
 * <ol>
 *   <li>upsert {@code verla_clarify_forms}（按 form_id 幂等）；</li>
 *   <li>写一条 assistant {@link VerlaMessage}（{@code blocksJson} 含 form 引用），
 *       让前端聊天历史能渲染问卷卡片，且 hydrate 上下文时能回放；</li>
 *   <li>把 message.id 回填到 {@code verla_clarify_forms.message_id}；</li>
 *   <li>bump conv version（消息变更必失效缓存）。</li>
 * </ol>
 * 详见 docs/V2/5.1 §3 / §4.2。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaClarifyFormEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.AGENT_CLARIFY_FORM_ISSUED);

    private static final String BLOCK_TYPE_CLARIFY = "clarify_form";

    private final VerlaClarifyFormRepository clarifyFormRepository;
    private final VerlaMessageRepository messageRepository;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaClarifyFormPayload p = parsePayload(env);
        if (p == null) {
            log.warn("[Verla/clarify] empty payload, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        if (p.getFormId() == null || p.getFormId().isBlank()) {
            log.warn("[Verla/clarify] AGENT_CLARIFY_FORM_ISSUED missing form_id, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }

        // 1) upsert clarify_form（先不带 messageId，写完 message 再回填）
        VerlaClarifyForm form = VerlaClarifyForm.builder()
                .formId(p.getFormId())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .messageId(p.getMessageId())
                .title(p.getTitle())
                .description(p.getDescription())
                .schemaJson(toJson(p.getSchema()))
                .status("OPEN")
                .expiresAt(toLocalDateTime(p.getExpiresAt()))
                .build();
        VerlaClarifyForm savedForm = clarifyFormRepository.upsertByFormId(form);

        // 2) 仅在首次（messageId 还没回填、且不是重放）时写 assistant message
        if (savedForm.getMessageId() == null) {
            VerlaMessage msg = buildAssistantMessage(row, p, savedForm);
            VerlaMessage savedMsg = messageRepository.save(msg);

            // 3) 回填 messageId
            VerlaClarifyForm patchBack = VerlaClarifyForm.builder()
                    .formId(savedForm.getFormId())
                    .conversationId(savedForm.getConversationId())
                    .turnId(savedForm.getTurnId())
                    .sessionId(savedForm.getSessionId())
                    .messageId(savedMsg.getId())
                    .schemaJson(savedForm.getSchemaJson())
                    .title(savedForm.getTitle())
                    .description(savedForm.getDescription())
                    .expiresAt(savedForm.getExpiresAt())
                    .build();
            clarifyFormRepository.upsertByFormId(patchBack);
            log.info("[Verla/clarify] form persisted formId={} cid={} messageId={}",
                    savedForm.getFormId(), savedForm.getConversationId(), savedMsg.getId());
        } else {
            log.info("[Verla/clarify] form replay/re-issue formId={} cid={} reused messageId={}",
                    savedForm.getFormId(), savedForm.getConversationId(), savedForm.getMessageId());
        }

        // 4) 消息变更必失效上下文缓存
        conversationRepository.incrementVersion(row.getConversationId());
    }

    private VerlaMessage buildAssistantMessage(VerlaEventInbox row,
                                               VerlaClarifyFormPayload p,
                                               VerlaClarifyForm savedForm) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", BLOCK_TYPE_CLARIFY);
        block.put("formId", savedForm.getFormId());
        if (p.getTitle() != null)       block.put("title", p.getTitle());
        if (p.getDescription() != null) block.put("description", p.getDescription());
        if (p.getSchema() != null)      block.put("schema", p.getSchema());

        Map<String, Object> blocksWrapper = new HashMap<>();
        blocksWrapper.put("blocks", java.util.List.of(block));

        return VerlaMessage.builder()
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .role("assistant")
                .sourceSessionId(row.getSessionId())
                .textContent(p.getDescription() != null ? p.getDescription()
                        : (p.getTitle() != null ? p.getTitle() : "请补充以下信息"))
                .blocksJson(toJson(blocksWrapper))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private VerlaClarifyFormPayload parsePayload(VerlaEventEnvelope env) {
        if (env == null || env.getPayload() == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(env.getPayload(), VerlaClarifyFormPayload.class);
        } catch (Exception e) {
            log.warn("[Verla/clarify] payload convert failed: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/clarify] json serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(java.time.Instant inst) {
        return DateTimeFormats.fromInstant(inst);
    }
}
