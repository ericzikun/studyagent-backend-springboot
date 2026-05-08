package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaArtifactStatus;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.payload.VerlaArtifactUpdatedPayload;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
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
            VerlaAgentEventType.ASSIGNMENT_ARTIFACT_UPDATED);

    private final VerlaArtifactRepository artifactRepository;
    private final VerlaConversationRepository conversationRepository;
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
