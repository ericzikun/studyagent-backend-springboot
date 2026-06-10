package com.studyagent.api.controller.verla;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.CommitEditProposalRequest;
import com.studyagent.api.dto.verla.response.CommitEditProposalResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import com.studyagent.infra.entity.verla.VerlaEditorContentEntity;
import com.studyagent.infra.mapper.verla.VerlaArtifactMapper;
import com.studyagent.infra.mapper.verla.VerlaEditorContentMapper;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.verla.VerlaArtifactEditProposal;
import com.studyagent.service.domain.verla.repo.VerlaArtifactEditProposalRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat With Assignment / write 模式 review 提案 commit 接口（设计 §4.8 / 协议 §9.3）。
 * <p>
 * 用户逐块 Accept/Reject 后一次性提交 decisions；后端校验 baseVersionNo 未漂移 →
 * 按 accepted hunks 由 base 正文重算整篇新正文 → 同事务 upsert artifact(version+1) +
 * 清空 editor 工作态（让 GET 回落 seed 新正文）+ proposal 置 COMMITTED（不可回退）。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations/{cid}/artifacts/edit-proposals")
@RequiredArgsConstructor
public class VerlaArtifactEditProposalController {

    private static final String MODE_REVIEW = "review";
    private static final String STATUS_ACCEPTED = "accepted";

    private final VerlaConversationService conversationService;
    private final VerlaArtifactEditProposalRepository proposalRepository;
    private final VerlaArtifactMapper artifactMapper;
    private final VerlaEditorContentMapper editorContentMapper;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/{proposalId}/commit")
    @Transactional(rollbackFor = Exception.class)
    public Result<CommitEditProposalResponseVO> commit(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable("cid") Long conversationId,
            @PathVariable("proposalId") String proposalId,
            @RequestBody @Valid CommitEditProposalRequest req) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, conversationId);

        VerlaArtifactEditProposal proposal = proposalRepository.findByProposalId(proposalId);
        if (proposal == null || !conversationId.equals(proposal.getConversationId())) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "editProposal");
        }
        if (!VerlaArtifactEditProposal.STATE_REVIEWING.equals(proposal.getState())) {
            // 已 commit / superseded / 失败：拒绝重复提交，提示前端重取快照。
            throw new BusinessException(ApiCode.BAD_REQUEST,
                    "proposal not in REVIEWING state: " + proposal.getState());
        }

        Map<String, List<Map<String, Object>>> changesByUid = parseChanges(proposal.getChangesJson());
        Map<String, Integer> baseVersionByUid = parseBaseVersions(proposal.getTargetsJson());
        Map<String, Set<String>> acceptedByUid = acceptedHunkIds(req.getDecisions());

        List<CommitEditProposalResponseVO.CommittedArtifact> committed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, List<Map<String, Object>>> entry : changesByUid.entrySet()) {
            String uid = entry.getKey();
            List<Map<String, Object>> hunks = entry.getValue();

            VerlaArtifactEntity artifact = artifactMapper.selectByUid(uid);
            if (artifact == null || !conversationId.equals(artifact.getConversationId())) {
                throw new BusinessException(ApiCode.TASK_NOT_FOUND, "artifact:" + uid);
            }
            // baseVersionNo 漂移校验：旧提案不得套到新文档（协议）。
            Integer base = baseVersionByUid.get(uid);
            if (base != null && artifact.getVersion() != null && !base.equals(artifact.getVersion())) {
                throw new BusinessException(ApiCode.BAD_REQUEST,
                        "artifact " + uid + " version drifted (base=" + base
                                + ", current=" + artifact.getVersion() + "), please reload");
            }

            Set<String> accepted = acceptedByUid.getOrDefault(uid, Set.of());
            String baseText = artifact.getBodyOrRef() != null ? artifact.getBodyOrRef() : "";
            String newText = applyAcceptedHunks(baseText, hunks, accepted);

            // 全部 rejected → 正文不变，仅记审计；artifact 不升版。
            if (!newText.equals(baseText)) {
                int newVersion = (artifact.getVersion() != null ? artifact.getVersion() : 1) + 1;
                artifact.setBodyOrRef(newText);
                artifact.setSizeBytes((long) newText.getBytes(StandardCharsets.UTF_8).length);
                artifact.setVersion(newVersion);
                artifact.setStatus("READY");
                artifact.setUpdatedAt(now);
                artifactMapper.updateById(artifact);
                // re-seed：清空 editor 工作态行，GET 无行时回落用新 artifact 正文作 seed（§9.1）。
                editorContentMapper.delete(new LambdaQueryWrapper<VerlaEditorContentEntity>()
                        .eq(VerlaEditorContentEntity::getConversationId, conversationId)
                        .eq(VerlaEditorContentEntity::getSourceArtifactUid, uid));
            }
            committed.add(CommitEditProposalResponseVO.CommittedArtifact.builder()
                    .artifactUid(uid)
                    .kind(artifact.getKind())
                    .versionNo(artifact.getVersion())
                    .build());
        }

        proposalRepository.markState(proposalId, VerlaArtifactEditProposal.STATE_COMMITTED);
        conversationRepository.incrementVersion(conversationId);
        log.info("[Verla/editProposal] COMMITTED pid={} cid={} artifacts={}",
                proposalId, conversationId, committed.size());

        return Result.success(CommitEditProposalResponseVO.builder()
                .proposalId(proposalId)
                .conversationId(String.valueOf(conversationId))
                .artifacts(committed)
                .build());
    }

    /**
     * 由 base 正文叠加 accepted hunks 算出整篇新正文。
     * 倒序应用（按 anchor.from 降序），避免前面替换移动后面 hunk 的 offset；
     * 以 originalText 二次校验定位（offset 命中失败回落首个 indexOf），防 base 漂移误改。
     */
    private String applyAcceptedHunks(String base, List<Map<String, Object>> hunks, Set<String> accepted) {
        List<Map<String, Object>> applicable = new ArrayList<>();
        for (Map<String, Object> h : hunks) {
            String id = str(h.get("id"));
            if (id != null && accepted.contains(id)) {
                applicable.add(h);
            }
        }
        // 按起点降序，从后往前替换。
        applicable.sort(Comparator.comparingInt((Map<String, Object> h) -> anchorFrom(h)).reversed());

        StringBuilder sb = new StringBuilder(base);
        for (Map<String, Object> h : applicable) {
            String originalText = str(h.getOrDefault("originalText", anchorOriginal(h)));
            String proposedText = str(h.get("proposedText"));
            if (proposedText == null) {
                proposedText = "";
            }
            if (originalText == null) {
                continue;
            }
            int from = anchorFrom(h);
            int to = anchorTo(h);
            // 优先按 offset 命中校验，失败则回落 indexOf（base 可能被前一轮编辑微调）。
            if (from >= 0 && to >= from && to <= sb.length()
                    && sb.substring(from, to).equals(originalText)) {
                sb.replace(from, to, proposedText);
            } else {
                int idx = sb.indexOf(originalText);
                if (idx >= 0) {
                    sb.replace(idx, idx + originalText.length(), proposedText);
                } else {
                    log.warn("[Verla/editProposal] hunk {} originalText not found, skipped", str(h.get("id")));
                }
            }
        }
        return sb.toString();
    }

    private Map<String, List<Map<String, Object>>> parseChanges(String changesJson) {
        if (changesJson == null || changesJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(changesJson,
                    new TypeReference<Map<String, List<Map<String, Object>>>>() {});
        } catch (Exception e) {
            log.warn("[Verla/editProposal] parse changes_json failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Integer> parseBaseVersions(String targetsJson) {
        Map<String, Integer> out = new java.util.HashMap<>();
        if (targetsJson == null || targetsJson.isBlank()) {
            return out;
        }
        try {
            List<Map<String, Object>> targets = objectMapper.readValue(targetsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> t : targets) {
                if (!MODE_REVIEW.equals(str(t.get("editMode")))) {
                    continue;
                }
                String uid = str(t.get("artifactUid"));
                Object base = t.get("baseVersionNo");
                if (uid != null && base instanceof Number num) {
                    out.put(uid, num.intValue());
                }
            }
        } catch (Exception e) {
            log.warn("[Verla/editProposal] parse targets_json failed: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, Set<String>> acceptedHunkIds(List<CommitEditProposalRequest.Decision> decisions) {
        Map<String, Set<String>> out = new java.util.HashMap<>();
        if (decisions == null) {
            return out;
        }
        for (CommitEditProposalRequest.Decision d : decisions) {
            if (d.getArtifactUid() == null || d.getHunkId() == null) {
                continue;
            }
            if (STATUS_ACCEPTED.equalsIgnoreCase(d.getStatus())) {
                out.computeIfAbsent(d.getArtifactUid(), k -> new HashSet<>()).add(d.getHunkId());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int anchorFrom(Map<String, Object> h) {
        Object anchor = h.get("anchor");
        if (anchor instanceof Map<?, ?> a && a.get("from") instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static int anchorTo(Map<String, Object> h) {
        Object anchor = h.get("anchor");
        if (anchor instanceof Map<?, ?> a && a.get("to") instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    private static String anchorOriginal(Map<String, Object> h) {
        Object anchor = h.get("anchor");
        if (anchor instanceof Map<?, ?> a) {
            Object o = a.get("originalText");
            return o == null ? null : String.valueOf(o);
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
