package com.studyagent.api.controller.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.domain.file.OssStorageService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulation Lab：单轮创建、状态与产物读取。
 *
 * 这里不复用 Assignment 的 plan/clarify/chat 入口；generate 复用既有
 * turn/session/outbox/SSE 基础设施，但会话 kind 固定为 SIMULATION，已生成的
 * conversation 不允许再次发送消息。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/simulations")
@RequiredArgsConstructor
public class VerlaSimulationController {

    private static final String KIND_SIMULATION_HTML = "simulation_html";
    private static final String INTENT_SIMULATION = "SIMULATION";
    private static final int MAX_PROMPT_CHARS = 6000;
    private static final int MAX_FILES = 3;
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ATTACHMENTS_JSON_CHARS = 32 * 1024;
    private static final int MAX_ACTIVE_RUNS = 1;
    private static final int MAX_DAILY_RUNS = 50;

    private final VerlaConversationService conversationService;
    private final VerlaTurnOrchestrator turnOrchestrator;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaAttachmentRepository attachmentRepository;
    private final OssStorageService ossStorageService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/config")
    public Result<Map<String, Object>> config(
            @RequestAttribute("clerkUserId") String clerkUserId) {
        ensureLogin(clerkUserId);
        return Result.success(Map.of(
                "createEnabled", true,
                "canCreate", true,
                "attachmentsEnabled", true,
                "maxFiles", MAX_FILES,
                "maxFileBytes", MAX_FILE_BYTES,
                "maxExtractedChars", 30000,
                "maxActiveRuns", MAX_ACTIVE_RUNS,
                "maxDailyRuns", MAX_DAILY_RUNS));
    }

    @PostMapping
    public Result<Map<String, Object>> create(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) CreateSimulationRequest req) {
        ensureLogin(clerkUserId);
        String prompt = normalizePrompt(req == null ? null : req.prompt());
        String title = titleFromPrompt(prompt);
        VerlaConversation conversation = conversationService.create(
                clerkUserId, title, "{\"mode\":\"simulation\"}", null);
        log.info("[Simulation] draft created cid={} user={}", conversation.getId(), clerkUserId);
        return Result.success(Map.of(
                "conversationId", VerlaConversationVO.fromPublic(conversation).getConversationId(),
                "status", "DRAFT"));
    }

    @PostMapping("/{cid}/generate")
    public Result<Map<String, Object>> generate(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody(required = false) GenerateSimulationRequest req) {
        ensureLogin(clerkUserId);
        String prompt = normalizePrompt(req == null ? null : req.prompt());
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        if (conversation.getPrimaryIntent() != null
                && !conversation.getPrimaryIntent().isBlank()
                && !INTENT_SIMULATION.equalsIgnoreCase(conversation.getPrimaryIntent())) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "conversation is not a simulation lab");
        }
        if (conversation.getLastTurnId() != null || (conversation.getTurnCount() != null && conversation.getTurnCount() > 0)) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "simulation lab already generated");
        }
        String attachmentsJson = req == null ? null : req.attachmentsJson();
        validateAttachments(attachmentsJson, cid, clerkUserId);
        enforceRunLimits(clerkUserId);
        SendMessageCommand command = SendMessageCommand.builder()
                .conversationId(cid)
                .userId(clerkUserId)
                .text(prompt)
                .attachmentsJson(attachmentsJson)
                .skipPlanIfPossible(true)
                .forceIntent(INTENT_SIMULATION)
                .outputLanguage(req == null ? null : req.outputLanguage())
                .build();
        SendMessageResult result = turnOrchestrator.onUserMessage(command);
        log.info("[Simulation] generate dispatched cid={} turn={} session={}",
                cid, result.getTurnId(), result.getAgentSessionId());
        return Result.success(Map.of(
                "turnId", result.getTurnId(),
                "sessionId", result.getAgentSessionId(),
                "status", "QUEUED"));
    }

    @GetMapping("/{cid}")
    public Result<Map<String, Object>> status(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        VerlaArtifact artifact = latestSimulationArtifact(cid);
        String status = resolveStatus(conversation, artifact);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", VerlaConversationVO.fromPublic(conversation).getConversationId());
        body.put("status", status);
        body.put("title", conversation.getTitle());
        if ("FAILED".equals(status)) {
            String errorMessage = sessionErrorMessage(lastSession(conversation));
            if (errorMessage != null && !errorMessage.isBlank()) {
                body.put("errorMessage", errorMessage);
            }
        }
        if (artifact != null) {
            body.put("latestArtifact", Map.of(
                    "artifactUid", artifact.getArtifactUid(),
                    "sizeBytes", artifact.getSizeBytes() == null ? 0L : artifact.getSizeBytes(),
                    "contentAvailable", hasContent(artifact)));
        }
        return Result.success(body);
    }

    @GetMapping("/{cid}/content")
    public Result<Map<String, Object>> content(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        VerlaArtifact artifact = latestSimulationArtifact(cid);
        if (artifact == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND.getCode(), "simulation artifact not found");
        }
        String html = readArtifactHtml(artifact);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifactUid", artifact.getArtifactUid());
        body.put("title", conversation.getTitle());
        body.put("html", html);
        body.put("meta", parseMeta(artifact.getMetaJson()));
        return Result.success(body);
    }

    @PostMapping("/{cid}/cancel")
    public Result<Void> cancel(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        if (conversation.getLastTurnId() == null) {
            return Result.success(null);
        }
        VerlaTurn turn = turnRepository.findById(conversation.getLastTurnId());
        Long sessionId = turn == null ? null
                : (turn.getAgentSessionId() != null ? turn.getAgentSessionId() : turn.getActiveSessionId());
        if (sessionId == null) {
            return Result.success(null);
        }
        VerlaSession session = sessionRepository.findById(sessionId);
        if (session == null || !"SIMULATION".equals(session.getKind())) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "not a simulation session");
        }
        turnOrchestrator.cancelAssignmentRun(sessionId);
        return Result.success(null);
    }

    @DeleteMapping("/{cid}")
    public Result<Void> deleteDraft(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        VerlaArtifact artifact = latestSimulationArtifact(cid);
        if (artifact != null && "READY".equalsIgnoreCase(artifact.getStatus())) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "generated simulation lab cannot be deleted");
        }
        if (activeSession(conversation) != null) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(), "cancel generation before deleting this lab");
        }
        conversationService.softDelete(clerkUserId, cid);
        return Result.success(null);
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }

    private void validateAttachments(String attachmentsJson, Long conversationId, String clerkUserId) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return;
        }
        if (attachmentsJson.length() > MAX_ATTACHMENTS_JSON_CHARS) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "attachmentsJson too large");
        }
        try {
            var node = objectMapper.readTree(attachmentsJson);
            if (!node.isArray()) {
                throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "attachmentsJson must be an array");
            }
            if (node.size() > MAX_FILES) {
                throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "at most " + MAX_FILES + " attachments");
            }
            for (var item : node) {
                String objectId = item.path("objectId").asText("");
                if (objectId.isBlank()) {
                    throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "attachment objectId required");
                }
                VerlaAttachment attachment = attachmentRepository.findByObjectId(objectId);
                if (attachment == null
                        || attachment.getDeletedAt() != null
                        || !conversationId.equals(attachment.getConversationId())
                        || (attachment.getUserId() != null
                        && !attachment.getUserId().equals(clerkUserId))) {
                    throw new BusinessException(ApiCode.NO_PERMISSION.getCode(),
                            "attachment does not belong to this lab");
                }
                long size = attachment.getSizeBytes() == null ? 0L : attachment.getSizeBytes();
                if (size > MAX_FILE_BYTES) {
                    throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "attachment exceeds 32 MB");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "invalid attachmentsJson");
        }
    }

    private void enforceRunLimits(String clerkUserId) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT s.id) FROM verla_sessions s "
                        + "INNER JOIN verla_conversations c ON c.id = s.conversation_id "
                        + "WHERE c.user_id = ? AND c.status <> 'deleted' AND s.kind = 'SIMULATION' "
                        + "AND s.status IN ('CREATED', 'DISPATCHING', 'RUNNING', 'CANCELLING')",
                Integer.class, clerkUserId);
        if (active != null && active >= MAX_ACTIVE_RUNS) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(),
                    "another simulation is still generating");
        }
        Integer daily = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM verla_turns t "
                        + "INNER JOIN verla_conversations c ON c.id = t.conversation_id "
                        + "WHERE c.user_id = ? AND c.status <> 'deleted' "
                        + "AND t.resolved_intent = 'SIMULATION' AND t.created_at >= ?",
                Integer.class, clerkUserId, LocalDateTime.now().minusHours(24));
        if (daily != null && daily >= MAX_DAILY_RUNS) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE.getCode(),
                    "daily simulation limit reached");
        }
    }

    private VerlaSession lastSession(VerlaConversation conversation) {
        if (conversation.getLastTurnId() == null) {
            return null;
        }
        VerlaTurn turn = turnRepository.findById(conversation.getLastTurnId());
        Long sessionId = turn == null ? null
                : (turn.getAgentSessionId() != null ? turn.getAgentSessionId() : turn.getActiveSessionId());
        return sessionId == null ? null : sessionRepository.findById(sessionId);
    }

    private VerlaSession activeSession(VerlaConversation conversation) {
        VerlaSession session = lastSession(conversation);
        if (session == null || session.getStatus() == null) {
            return null;
        }
        String status = session.getStatus().toUpperCase();
        if (status.contains("SUCCEEDED") || status.contains("FAILED") || status.contains("CANCELLED")) {
            return null;
        }
        return session;
    }

    private String sessionErrorMessage(VerlaSession session) {
        if (session == null || session.getResultJson() == null || session.getResultJson().isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(session.getResultJson());
            for (String key : List.of("errorMessage", "error", "message", "reason")) {
                String value = node.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // A malformed result JSON must not break the status endpoint.
        }
        return null;
    }

    private String normalizePrompt(String raw) {
        String prompt = raw == null ? "" : raw.trim();
        if (prompt.isEmpty()) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "prompt required");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new BusinessException(ApiCode.PARAM_ERROR.getCode(), "prompt too long");
        }
        return prompt;
    }

    private String titleFromPrompt(String prompt) {
        String oneLine = prompt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40);
    }

    private VerlaArtifact latestSimulationArtifact(Long conversationId) {
        List<VerlaArtifact> artifacts = artifactRepository.findByConversation(conversationId);
        return artifacts.stream()
                .filter(a -> KIND_SIMULATION_HTML.equalsIgnoreCase(a.getKind()))
                .max(Comparator.comparing(a -> a.getUpdatedAt() == null
                        ? java.time.LocalDateTime.MIN : a.getUpdatedAt()))
                .orElse(null);
    }

    private String resolveStatus(VerlaConversation conversation, VerlaArtifact artifact) {
        if (artifact != null && "READY".equalsIgnoreCase(artifact.getStatus())) {
            return "READY";
        }
        if (conversation.getLastTurnId() == null) {
            return "DRAFT";
        }
        VerlaSession session = lastSession(conversation);
        if (session == null) {
            return "QUEUED";
        }
        String s = session.getStatus() == null ? "" : session.getStatus().toUpperCase();
        if (s.contains("FAILED")) return "FAILED";
        if (s.contains("CANCEL")) return "CANCELLED";
        if (s.contains("SUCCEEDED") || s.contains("COMPLETED")) return "READY";
        if (s.contains("RUNNING")) return "GENERATING";
        if (s.contains("DISPATCH")) return "QUEUED";
        return "DRAFT";
    }

    private boolean hasContent(VerlaArtifact artifact) {
        return (artifact.getBodyOrRef() != null && !artifact.getBodyOrRef().isBlank())
                || (artifact.getContentRef() != null && !artifact.getContentRef().isBlank());
    }

    private String readArtifactHtml(VerlaArtifact artifact) {
        if (artifact.getBodyOrRef() != null && !artifact.getBodyOrRef().isBlank()) {
            return artifact.getBodyOrRef();
        }
        String ref = artifact.getContentRef();
        if (ref == null || ref.isBlank()) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND.getCode(), "simulation content missing");
        }
        String key = ref.startsWith("oss://") ? ref.substring("oss://".length()) : ref;
        int slash = key.indexOf('/');
        if (slash >= 0) {
            key = key.substring(slash + 1);
        }
        byte[] bytes = ossStorageService.getObjectBytes(key);
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND.getCode(), "simulation content unavailable");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object parseMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metaJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record CreateSimulationRequest(String prompt) {
    }

    public record GenerateSimulationRequest(
            String prompt,
            String attachmentsJson,
            String outputLanguage) {
    }
}
