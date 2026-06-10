package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.repo.VerlaArtifactEditProposalRepository;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat With Assignment / write 模式 Edit Proposal 事件 handler（设计 §4.3-B）。
 * <p>
 * 处理 {@link VerlaAgentEventType#ARTIFACT_EDIT_PROPOSAL_STARTED}/{@code _READY}/{@code _FAILED}，
 * 把提案落 {@code verla_artifact_edit_proposals}。SSE 透传由 inbox 通用路径负责，这里只持久化。
 * <ul>
 *   <li>STARTED：插 GENERATING + targets（为 review 目标补 baseVersionNo），supersede 旧活跃提案。</li>
 *   <li>READY：review 目标 → REVIEWING + changes_json；若全是 overwrite（无待确认 diff）→ COMMITTED。</li>
 *   <li>FAILED：state=FAILED + errorMessage。</li>
 * </ul>
 * overwrite 目标的实际正文走现有 {@code *_ARTIFACT_UPDATED} → {@link VerlaArtifactEventHandler}，这里不碰 artifact。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaArtifactEditProposalEventHandler implements VerlaEventHandler {

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.ARTIFACT_EDIT_PROPOSAL_STARTED,
            VerlaAgentEventType.ARTIFACT_EDIT_PROPOSAL_READY,
            VerlaAgentEventType.ARTIFACT_EDIT_PROPOSAL_FAILED);

    private static final String MODE_REVIEW = "review";

    private final VerlaArtifactEditProposalRepository proposalRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        Map<String, Object> payload = asMap(env == null ? null : env.getPayload());
        if (payload == null) {
            log.warn("[Verla/editProposal] empty payload sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        String proposalId = str(payload.get("proposalId"));
        if (proposalId == null || proposalId.isBlank()) {
            log.warn("[Verla/editProposal] missing proposalId sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        VerlaAgentEventType type = VerlaAgentEventType.valueOf(row.getEventType());
        List<Map<String, Object>> targets = asList(payload.get("targets"));

        switch (type) {
            case ARTIFACT_EDIT_PROPOSAL_STARTED -> {
                enrichBaseVersions(targets);
                proposalRepository.upsertByProposalId(VerlaArtifactEditProposal.builder()
                        .proposalId(proposalId)
                        .conversationId(row.getConversationId())
                        .turnId(row.getTurnId())
                        .state(VerlaArtifactEditProposal.STATE_GENERATING)
                        .targetsJson(toJson(targets))
                        .build());
                // 同文件多轮编辑：把旧活跃提案置 SUPERSEDED，避免堆叠（协议 §9.3）。
                proposalRepository.supersedeActiveExcept(row.getConversationId(), proposalId);
                log.info("[Verla/editProposal] STARTED pid={} cid={} targets={}",
                        proposalId, row.getConversationId(), targets.size());
            }
            case ARTIFACT_EDIT_PROPOSAL_READY -> {
                enrichBaseVersions(targets);
                Map<String, Object> changesByUid = collectReviewChanges(targets);
                boolean hasReview = !changesByUid.isEmpty();
                proposalRepository.upsertByProposalId(VerlaArtifactEditProposal.builder()
                        .proposalId(proposalId)
                        .conversationId(row.getConversationId())
                        .turnId(row.getTurnId())
                        // 无待确认 review 目标（全 overwrite）→ 无后续 commit，直接 COMMITTED 解锁。
                        .state(hasReview
                                ? VerlaArtifactEditProposal.STATE_REVIEWING
                                : VerlaArtifactEditProposal.STATE_COMMITTED)
                        .targetsJson(toJson(targets))
                        .changesJson(hasReview ? toJson(changesByUid) : null)
                        .build());
                log.info("[Verla/editProposal] READY pid={} cid={} reviewTargets={}",
                        proposalId, row.getConversationId(), changesByUid.size());
            }
            case ARTIFACT_EDIT_PROPOSAL_FAILED -> {
                proposalRepository.upsertByProposalId(VerlaArtifactEditProposal.builder()
                        .proposalId(proposalId)
                        .conversationId(row.getConversationId())
                        .turnId(row.getTurnId())
                        .state(VerlaArtifactEditProposal.STATE_FAILED)
                        .targetsJson(targets.isEmpty() ? null : toJson(targets))
                        .errorMessage(str(payload.getOrDefault("message", payload.get("errorMessage"))))
                        .build());
                log.info("[Verla/editProposal] FAILED pid={} cid={}", proposalId, row.getConversationId());
            }
            default -> { /* unreachable */ }
        }
    }

    /** 为 review 目标补 baseVersionNo（以服务端当前 artifact 版本为准，commit 时防漂移）。 */
    private void enrichBaseVersions(List<Map<String, Object>> targets) {
        for (Map<String, Object> t : targets) {
            if (!MODE_REVIEW.equals(str(t.get("editMode")))) {
                continue;
            }
            if (t.get("baseVersionNo") != null) {
                continue;
            }
            String uid = str(t.get("artifactUid"));
            if (uid == null || uid.isBlank()) {
                continue;
            }
            VerlaArtifact artifact = artifactRepository.findByUid(uid);
            if (artifact != null && artifact.getVersion() != null) {
                t.put("baseVersionNo", artifact.getVersion());
            }
        }
    }

    /** review 目标的 changes（hunks）按 artifactUid 分组，落 changes_json。 */
    private Map<String, Object> collectReviewChanges(List<Map<String, Object>> targets) {
        Map<String, Object> byUid = new LinkedHashMap<>();
        for (Map<String, Object> t : targets) {
            if (!MODE_REVIEW.equals(str(t.get("editMode")))) {
                continue;
            }
            String uid = str(t.get("artifactUid"));
            Object changes = t.get("changes");
            if (uid != null && changes instanceof List<?> list && !list.isEmpty()) {
                byUid.put(uid, list);
            }
        }
        return byUid;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        if (!(o instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[Verla/editProposal] json serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
