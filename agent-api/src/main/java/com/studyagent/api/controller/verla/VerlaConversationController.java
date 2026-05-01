package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.CreateConversationRequest;
import com.studyagent.api.dto.verla.request.PatchConversationRequest;
import com.studyagent.api.dto.verla.request.SendMessageRequest;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.SendMessageResponseVO;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.dto.SendMessageCommand;
import com.studyagent.service.application.verla.dto.SendMessageResult;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
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
        String workspaceJson = null;
        if (req != null && req.getWorkspace() != null) {
            workspaceJson = String.valueOf(req.getWorkspace());
        }
        VerlaConversation c = conversationService.create(clerkUserId, title, workspaceJson);
        return Result.success(VerlaConversationVO.from(c));
    }

    // ========================================================
    // 2) GET /v1/verla/conversations  ——  列表
    // ========================================================
    @GetMapping
    public Result<List<VerlaConversationVO>> list(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        ensureLogin(clerkUserId);
        List<VerlaConversation> conversations = conversationService.list(clerkUserId, pageNo, pageSize);
        return Result.success(conversations.stream()
                .map(VerlaConversationVO::from).collect(Collectors.toList()));
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
        return Result.success(VerlaConversationVO.from(c));
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
        SendMessageCommand cmd = SendMessageCommand.builder()
                .conversationId(cid)
                .userId(clerkUserId)
                .text(req.getText())
                .clientMessageId(req.getClientMessageId())
                .attachmentsJson(writeAttachmentsJson(req.getAttachments()))
                .skipPlanIfPossible(req.getSkipPlanIfPossible() == null || req.getSkipPlanIfPossible())
                .build();
        SendMessageResult result = turnOrchestrator.onUserMessage(cmd);
        return Result.success(SendMessageResponseVO.from(result));
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
