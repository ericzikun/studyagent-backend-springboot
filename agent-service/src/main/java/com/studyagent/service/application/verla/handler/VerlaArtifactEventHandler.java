package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaArtifactStatus;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaArtifactUpdatedPayload;
import com.studyagent.service.application.verla.HumanizerDetectionMatchService;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Verla artifact 写路径 handler（PR-V2-02）。
 * <p>
 * 处理 {@link VerlaAgentEventType#AGENT_ARTIFACT_UPDATED}：把 payload 转 typed
 * {@link VerlaArtifactUpdatedPayload} 后按 {@code artifact_uid} 幂等 upsert。
 * <p>
 * 详见 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 / §4 / §5。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaArtifactEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.AGENT_ARTIFACT_UPDATED,
            VerlaAgentEventType.ASSIGNMENT_ARTIFACT_UPDATED,
            VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED);

    private final VerlaArtifactRepository artifactRepository;
    private final VerlaAttachmentRepository attachmentRepository;
    private final VerlaAttachmentService attachmentService;
    private final VerlaConversationRepository conversationRepository;
    private final HumanizerDetectionMatchService humanizerDetectionMatchService;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaArtifactUpdatedPayload p = parsePayload(env);
        if (p == null) {
            log.warn("[Verla/artifact] empty payload, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        if (p.getArtifactUid() == null || p.getArtifactUid().isBlank()) {
            String kind = p.getKind() != null && !p.getKind().isBlank() ? p.getKind() : "artifact";
            p.setArtifactUid("artifact_" + row.getConversationId() + "_" + row.getTurnId()
                    + "_" + row.getSessionId() + "_" + kind);
            log.debug("[Verla/artifact] synthesized artifact_uid={} sessionId={}",
                    p.getArtifactUid(), row.getSessionId());
        }
        hydrateBodyFromSourceObject(row, env, p);

        VerlaArtifact patch = VerlaArtifact.builder()
                .artifactUid(p.getArtifactUid())
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .sourceMessageId(p.getSourceMessageId())
                .sourceObjectId(p.getSourceObjectId())
                .kind(p.getKind())
                .mime(p.getMime())
                .summary(p.getSummary())
                .contentRef(p.getContentRef())
                .bodyOrRef(p.getBodyOrRef())
                .status(p.getStatus() != null ? p.getStatus() : VerlaArtifactStatus.READY.name())
                .sizeBytes(p.getSizeBytes())
                .version(p.getVersion())
                .metaJson(toJson(p.getMeta()))
                .build();

        VerlaArtifact saved = artifactRepository.upsertByUid(patch);
        log.info("[Verla/artifact] upsert ok cid={} uid={} kind={} version={} status={}",
                saved.getConversationId(), saved.getArtifactUid(), saved.getKind(),
                saved.getVersion(), saved.getStatus());

        // V2：Humanizer 汇总结果写入 Detection 粘贴匹配索引
        if (HumanizerDetectionMatchService.ARTIFACT_KIND_HUMANIZER_RESULT
                .equalsIgnoreCase(saved.getKind() == null ? "" : saved.getKind().trim())) {
            humanizerDetectionMatchService.recordFromHumanizerArtifact(saved, p.getMeta());
        }

        // 上下文 cache 失效：artifact 变更必 bump conv version
        conversationRepository.incrementVersion(row.getConversationId());
    }

    private VerlaArtifactUpdatedPayload parsePayload(VerlaEventEnvelope env) {
        if (env == null || env.getPayload() == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(env.getPayload(), VerlaArtifactUpdatedPayload.class);
        } catch (Exception e) {
            log.warn("[Verla/artifact] payload convert failed: {}", e.getMessage());
            return null;
        }
    }

    private void hydrateBodyFromSourceObject(VerlaEventInbox row, VerlaEventEnvelope env,
                                             VerlaArtifactUpdatedPayload p) {
        if (p.getBodyOrRef() != null && !p.getBodyOrRef().isBlank()) {
            return;
        }
        if (p.getSourceObjectId() == null || p.getSourceObjectId().isBlank()) {
            return;
        }
        // 二进制产物（如 coding 项目里的图片）不可解码成 UTF-8 bodyOrRef：
        // 已有 contentRef 或 meta.binary 标记时，正文应由前端走单文件接口按 contentRef 取。
        if (isBinaryArtifact(p)) {
            return;
        }

        try {
            VerlaAttachment attachment = attachmentRepository.findByObjectId(p.getSourceObjectId());
            if (attachment == null) {
                log.warn("[Verla/artifact] sourceObjectId={} not found, skip body hydrate",
                        p.getSourceObjectId());
                return;
            }
            if (row.getConversationId() != null
                    && attachment.getConversationId() != null
                    && !row.getConversationId().equals(attachment.getConversationId())) {
                log.warn("[Verla/artifact] sourceObjectId={} conversation mismatch rowCid={} attCid={}",
                        p.getSourceObjectId(), row.getConversationId(), attachment.getConversationId());
                return;
            }

            byte[] bytes = attachmentService.loadAttachmentBytes(p.getSourceObjectId());
            if (bytes == null || bytes.length == 0) {
                log.warn("[Verla/artifact] sourceObjectId={} has no readable bytes (cache miss + OSS/file fallback also empty)",
                        p.getSourceObjectId());
                return;
            }

            String body = new String(bytes, StandardCharsets.UTF_8);
            p.setBodyOrRef(body);
            p.setSizeBytes((long) bytes.length);
            mutateSsePayload(env, body, bytes.length);
        } catch (Exception e) {
            log.warn("[Verla/artifact] hydrate body failed sourceObjectId={}: {}",
                    p.getSourceObjectId(), e.getMessage());
        }
    }

    private boolean isBinaryArtifact(VerlaArtifactUpdatedPayload p) {
        if (p.getContentRef() != null && !p.getContentRef().isBlank()) {
            return true;
        }
        Map<String, Object> meta = p.getMeta();
        return meta != null && Boolean.TRUE.equals(meta.get("binary"));
    }

    @SuppressWarnings("unchecked")
    private void mutateSsePayload(VerlaEventEnvelope env, String body, int sizeBytes) {
        if (env == null || !(env.getPayload() instanceof Map)) {
            return;
        }
        Map<String, Object> payload = (Map<String, Object>) env.getPayload();
        payload.put("bodyOrRef", body);
        payload.put("sizeBytes", sizeBytes);
        payload.putIfAbsent("status", VerlaArtifactStatus.READY.name());
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/artifact] meta serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
