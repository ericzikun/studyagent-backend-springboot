package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.AssignmentClarifyContinueRequest;
import com.studyagent.api.dto.verla.request.CreateConversationRequest;
import com.studyagent.api.dto.verla.request.PatchConversationRequest;
import com.studyagent.api.dto.verla.request.PlanConfirmRequest;
import com.studyagent.api.dto.verla.request.SendMessageRequest;
import com.studyagent.api.dto.verla.response.AssignmentRuntimeSnapshotVO;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.PlanConfirmResponseVO;
import com.studyagent.api.dto.verla.response.SendMessageResponseVO;
import com.studyagent.api.dto.verla.response.VerlaConversationPageVO;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaConversationListSegment;
import com.studyagent.service.application.verla.AssignmentRuntimeSnapshotService;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaConversationDashboardStatusService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.PlanConfirmResult;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.application.verla.dto.VerlaConversationListSlice;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.state.ConversationStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verla Conversation REST 控制器
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §21.1 / §21.2。
 * 鉴权沿用现网 AuthInterceptor，从 request attribute 取 clerkUserId。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations")
@RequiredArgsConstructor
public class VerlaConversationController {

    private final VerlaConversationService conversationService;
    private final VerlaConversationDashboardStatusService dashboardStatusService;
    private final AssignmentRuntimeSnapshotService assignmentRuntimeSnapshotService;
    private final VerlaTurnOrchestrator turnOrchestrator;
    private final ObjectMapper objectMapper;

    // ========================================================
    // 1) POST /v1/verla/conversations  ——  创建对话 Tab
    // ========================================================
    @PostMapping
    public Result<VerlaConversationVO> create(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) CreateConversationRequest req) {
        ensureLogin(clerkUserId);
        String title = req == null ? null : req.getTitle();
        String primaryIntent = req == null ? null : req.getPrimaryIntent();
        String workspaceJson = null;
        if (req != null && req.getWorkspace() != null) {
            workspaceJson = String.valueOf(req.getWorkspace());
        }
        VerlaConversation c = conversationService.create(clerkUserId, title, workspaceJson, primaryIntent);
        return Result.success(VerlaConversationVO.from(c));
    }

    // ========================================================
    // 2) GET /v1/verla/conversations  ——  分页列表（Dashboard 右侧分栏：segment / status）
    // ========================================================
    @GetMapping
    public Result<VerlaConversationPageVO> list(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "segment", required = false) String segment,
            @RequestParam(value = "status", required = false) String status) {
        ensureLogin(clerkUserId);
        VerlaConversationListSegment seg = parseSegment(segment);
        ConversationStatus st = parseConversationStatusFilter(status);
        VerlaConversationListSlice slice =
                conversationService.listConversations(clerkUserId, pageNo, pageSize, seg, st);
        return Result.success(VerlaConversationPageVO.fromSlice(
                slice,
                dashboardStatusService.resolveAll(slice.records())));
    }

    // ========================================================
    // 3) GET /v1/verla/conversations/{cid}  ——  详情
    // ========================================================
    @GetMapping("/{cid}")
    public Result<VerlaConversationVO> get(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        VerlaConversation c = conversationService.getOwned(clerkUserId, cid);
        return Result.success(VerlaConversationVO.from(c, dashboardStatusService.resolve(c)));
    }

    // ========================================================
    // 4) PATCH /v1/verla/conversations/{cid}  ——  改标题 / 归档
    // ========================================================
    @PatchMapping("/{cid}")
    public Result<VerlaConversationVO> patch(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody PatchConversationRequest req) {
        ensureLogin(clerkUserId);
        VerlaConversation c;
        if (req.getTitle() != null) {
            c = conversationService.rename(clerkUserId, cid, req.getTitle());
        } else {
            c = conversationService.getOwned(clerkUserId, cid);
        }
        if (Boolean.TRUE.equals(req.getArchive())) {
            c = conversationService.archive(clerkUserId, cid);
        } else if (Boolean.FALSE.equals(req.getArchive())) {
            c = conversationService.restore(clerkUserId, cid);
        }
        return Result.success(VerlaConversationVO.from(c));
    }

    // ========================================================
    // 5) DELETE /v1/verla/conversations/{cid}  ——  软删除
    // ========================================================
    @DeleteMapping("/{cid}")
    public Result<Void> delete(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.softDelete(clerkUserId, cid);
        return Result.success(null);
    }

    // ========================================================
    // 6) POST /v1/verla/conversations/{cid}/messages  ——  发送用户消息（核心入口）
    // ========================================================
    @PostMapping("/{cid}/messages")
    public Result<SendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody @Valid SendMessageRequest req) {
        ensureLogin(clerkUserId);
        log.info("[Verla] sendMessage HTTP: cid={}, forceIntent='{}', skipPlan={}, text='{}'",
                cid, req.getForceIntent(), req.getSkipPlanIfPossible(),
                req.getText() == null ? null : req.getText().substring(0, Math.min(50, req.getText().length())));
        SendMessageCommand cmd = SendMessageCommand.builder()
                .conversationId(cid)
                .userId(clerkUserId)
                .text(req.getText())
                .clientMessageId(req.getClientMessageId())
                .attachmentsJson(writeAttachmentsJson(req.getAttachments()))
                .skipPlanIfPossible(req.getSkipPlanIfPossible() == null || req.getSkipPlanIfPossible())
                .forceIntent(req.getForceIntent())
                .build();
        SendMessageResult result = turnOrchestrator.onUserMessage(cmd);
        return Result.success(SendMessageResponseVO.from(result));
    }

    // ========================================================
    // 6.1) POST /v1/verla/conversations/{cid}/assignment/clarify/start
    //      —— 用户确认进入作业完成功能后先启动 requirement clarify stage_0
    // ========================================================
    @PostMapping("/{cid}/assignment/clarify/start")
    public Result<SendMessageResponseVO> startAssignmentClarify(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        SendMessageResult result = turnOrchestrator.startAssignmentClarifyFromLatestPlan(clerkUserId, cid);
        return Result.success(SendMessageResponseVO.from(result));
    }

    @PostMapping("/{cid}/plan/confirm")
    public Result<PlanConfirmResponseVO> confirmPlan(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody @Valid PlanConfirmRequest req) {
        ensureLogin(clerkUserId);
        PlanConfirmResult result = turnOrchestrator.confirmLatestPlan(
                clerkUserId,
                cid,
                Boolean.TRUE.equals(req.getConfirmed()),
                req.getSomethingElseText());
        return Result.success(PlanConfirmResponseVO.from(result));
    }

    @PostMapping("/{cid}/assignment/clarify/continue")
    public Result<SendMessageResponseVO> continueAssignmentClarify(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody(required = false) AssignmentClarifyContinueRequest req) {
        ensureLogin(clerkUserId);
        AssignmentClarifyContinueRequest body = req == null ? new AssignmentClarifyContinueRequest() : req;
        boolean userUnderstood = Boolean.TRUE.equals(body.getUserUnderstood());
        SendMessageResult result = turnOrchestrator.continueAssignmentClarify(
                clerkUserId,
                cid,
                body.getSessionId(),
                body.getUserChoice(),
                userUnderstood,
                body.getText(),
                body.getObjectIds());
        return Result.success(SendMessageResponseVO.from(result));
    }

    @PostMapping("/{cid}/assignment/clarify/finalize")
    public Result<SendMessageResponseVO> finalizeAssignmentClarify(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody(required = false) AssignmentClarifyContinueRequest req) {
        ensureLogin(clerkUserId);
        AssignmentClarifyContinueRequest body = req == null ? new AssignmentClarifyContinueRequest() : req;
        SendMessageResult result = turnOrchestrator.finalizeAssignmentClarify(
                clerkUserId,
                cid,
                body.getSessionId(),
                body.getReservedFields(),
                body.getAppendAskAnswers(),
                body.getRequirementForm(),
                body.getObjectIds());
        return Result.success(SendMessageResponseVO.from(result));
    }

    @PostMapping("/{cid}/assignment/run")
    public Result<SendMessageResponseVO> runAssignment(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        SendMessageResult result = turnOrchestrator.startAssignmentRunFromFinalClarify(clerkUserId, cid);
        return Result.success(SendMessageResponseVO.from(result));
    }

    // ========================================================
    // 6.5) GET /v1/verla/conversations/{cid}/assignment/runtime-snapshot
    //      —— Assignment refresh/reopen recovery snapshot
    // ========================================================
    @GetMapping("/{cid}/assignment/runtime-snapshot")
    public Result<AssignmentRuntimeSnapshotVO> getAssignmentRuntimeSnapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        return Result.success(AssignmentRuntimeSnapshotVO.from(
                assignmentRuntimeSnapshotService.getSnapshot(cid)));
    }

    // ========================================================
    // 7) GET /v1/verla/conversations/{cid}/messages  ——  历史消息（游标分页）
    // ========================================================
    @GetMapping("/{cid}/messages")
    public Result<MessagePageVO> listMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        ensureLogin(clerkUserId);
        // 校验所有权（不可写也允许查）
        conversationService.getOwned(clerkUserId, cid);
        List<VerlaMessage> page = conversationService.listMessages(cid, cursor, limit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .build());
    }

    // ========================================================
    // helper
    // ========================================================
    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }

    /**
     * @param raw {@code assignment} | {@code learning} | {@code ai_writing}，大小写不敏感；空则不过滤栏目
     */
    private static VerlaConversationListSegment parseSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (VerlaConversationListSegment s : VerlaConversationListSegment.values()) {
            if (s.getQueryKey().equals(key)) {
                return s;
            }
        }
        throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "unknown conversation segment: " + raw);
    }

    /**
     * @param raw {@code active} | {@code archived}（与 DB 小写一致）；空则包含两种（仍排除 deleted）
     */
    private static ConversationStatus parseConversationStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            ConversationStatus st = ConversationStatus.fromDb(raw.trim());
            if (st == ConversationStatus.DELETED) {
                throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "status cannot be deleted");
            }
            return st;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiCode.PARAM_VALIDATION_FAILED, "unknown conversation status: " + raw);
        }
    }

    private String writeAttachmentsJson(List<Map<String, Object>> attachments) {
        if (attachments == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "attachments must be JSON-serializable");
        }
    }
}
