package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaAttachmentParsedPayload;
import com.studyagent.service.application.verla.VerlaFileChatMetadataHelper;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatPanelState;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verla 附件解析事件 handler（PR-V2-02）。
 * <p>
 * 处理 {@link VerlaAgentEventType#ATTACHMENT_PARSED}：
 * <ul>
 *   <li>同 eventType 多 status：PARSING（progress 推进） / PARSED（最终摘要 + primaryArtifactUid）
 *       / FAILED（错误）；</li>
 *   <li>底层 {@code updateParseProgress} 守卫终态不可回退；</li>
 *   <li>仅在 PARSED 时 bump conv version（让前端刷新文件卡片摘要），
 *       PARSING 进度不影响上下文 hydrate，避免缓存频繁失效。</li>
 * </ul>
 * 详见 docs/V2/5.1 §3 / §4.3 / §6 / §8。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaAttachmentParsedEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.ATTACHMENT_PARSED);

    private final VerlaAttachmentRepository attachmentRepository;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAttachmentParsedPayload p = parsePayload(env);
        if (p == null) {
            log.warn("[Verla/attachment] empty payload, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        if (p.getObjectId() == null || p.getObjectId().isBlank()) {
            log.warn("[Verla/attachment] ATTACHMENT_PARSED missing object_id, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }

        String status = p.getStatus() != null ? p.getStatus() : VerlaAttachmentStatus.PARSING.name();

        VerlaAttachment existing = attachmentRepository.findByObjectId(p.getObjectId());
        VerlaAttachment patch = VerlaAttachment.builder()
                .objectId(p.getObjectId())
                .turnId(row.getTurnId())
                .status(status)
                .parseProgress(p.getProgress())
                .parseError(truncate(p.getErrorMessage(), 1024))
                .summary(truncate(p.getSummary(), 1024))
                .primaryArtifactUid(p.getPrimaryArtifactUid())
                .metaJson(buildAttachmentMetaJson(existing, status, p))
                .markdownContent(p.getMarkdownContent())
                .imagesJson(p.getImagesJson())
                .build();

        try {
            VerlaAttachment saved = attachmentRepository.updateParseProgress(patch);
            log.info("[Verla/attachment] {} objectId={} cid={} status={} progress={}",
                    saved.getStatus(), saved.getObjectId(), saved.getConversationId(),
                    saved.getStatus(), saved.getParseProgress());

            // 仅在终态变化时影响上下文（PARSED：摘要可注入；FAILED：前端要看到错误标记）
            VerlaAttachmentStatus s = safeStatus(saved.getStatus());
            if (s != null && s.isTerminal()) {
                conversationRepository.incrementVersion(row.getConversationId());
            }
        } catch (IllegalStateException notFound) {
            // attachment 还没创建（finalize 路径未走完或 mock 顺序错乱）
            // 不抛出避免阻塞 cursor 推进；运维侧靠日志告警
            log.warn("[Verla/attachment] objectId={} not found yet, drop event seq={}",
                    p.getObjectId(), row.getEventSeq());
        }
    }

    private VerlaAttachmentParsedPayload parsePayload(VerlaEventEnvelope env) {
        if (env == null || env.getPayload() == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(env.getPayload(), VerlaAttachmentParsedPayload.class);
        } catch (Exception e) {
            log.warn("[Verla/attachment] payload convert failed: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/attachment] meta serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildAttachmentMetaJson(VerlaAttachment existing,
                                           String status,
                                           VerlaAttachmentParsedPayload payload) {
        String mergedRoot = mergeRootMetaJson(existing == null ? null : existing.getMetaJson(),
                payload == null ? null : payload.getMeta());
        FileChatPanelState current = VerlaFileChatMetadataHelper.readAttachmentState(existing);
        FileChatPanelState next = nextFileChatState(status, payload, current);
        return VerlaFileChatMetadataHelper.writeAttachmentState(mergedRoot, next);
    }

    private String mergeRootMetaJson(String currentMetaJson, Map<String, Object> meta) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (currentMetaJson == null || currentMetaJson.isBlank()) {
                root = objectMapper.createObjectNode();
            } else {
                com.fasterxml.jackson.databind.JsonNode current = objectMapper.readTree(currentMetaJson);
                root = current instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode
                        ? objectNode.deepCopy()
                        : objectMapper.createObjectNode();
            }
            if (meta != null) {
                for (Map.Entry<String, Object> entry : meta.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || "fileChat".equals(entry.getKey())) {
                        continue;
                    }
                    root.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[Verla/attachment] merge meta failed: {}", e.getMessage());
            return toJson(meta);
        }
    }

    private FileChatPanelState nextFileChatState(String rawStatus,
                                                 VerlaAttachmentParsedPayload payload,
                                                 FileChatPanelState current) {
        FileChatPanelState safeCurrent = current == null ? FileChatPanelState.builder()
                .analysis(FileChatAnalysisState.pending())
                .suggestedQuestions(List.of())
                .build() : current;
        VerlaAttachmentStatus status = safeStatus(rawStatus);
        if (status == VerlaAttachmentStatus.PARSED) {
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.builder()
                            .status(FileChatAnalysisStatus.READY)
                            .text(truncate(payload == null ? null : payload.getSummary(), 1024) == null
                                    ? "" : truncate(payload.getSummary(), 1024))
                            .build())
                    .suggestedQuestions(payload == null || payload.getSuggestedQuestions() == null
                            ? List.of() : payload.getSuggestedQuestions())
                    .updatedAt(LocalDateTime.now().toString())
                    .build();
        }
        if (status == VerlaAttachmentStatus.FAILED) {
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.builder()
                            .status(FileChatAnalysisStatus.FAILED)
                            .text("")
                            .build())
                    .suggestedQuestions(List.of())
                    .updatedAt(LocalDateTime.now().toString())
                    .build();
        }
        return FileChatPanelState.builder()
                .analysis(FileChatAnalysisState.builder()
                        .status(FileChatAnalysisStatus.PENDING)
                        .text("")
                        .build())
                .suggestedQuestions(List.of())
                .updatedAt(safeCurrent.getUpdatedAt())
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static VerlaAttachmentStatus safeStatus(String s) {
        if (s == null) return null;
        try { return VerlaAttachmentStatus.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }
}
