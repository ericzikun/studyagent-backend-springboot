package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.AssignmentChatSendMessageRequest;
import com.studyagent.api.dto.verla.response.AssignmentChatSendMessageResponseVO;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Chat With Assignment：作业完成后，用户在 assignment 左栏对生成的 artifacts 追问 / 修改。
 * <p>
 * 历史隔离（scene=ASSIGNMENT_CHAT）：左栏可见、可回看，但不进主对话线程。
 * read / write 由后端模型按输入自行判定，前端不传 mode（设计 §1.5）。
 */
@RestController
@RequestMapping("/v1/verla/conversations")
@RequiredArgsConstructor
public class VerlaAssignmentChatController {

    private final VerlaTurnOrchestrator turnOrchestrator;
    private final VerlaConversationService conversationService;
    private final VerlaMessageRepository messageRepository;

    @GetMapping("/{cid}/assignment-chat/messages")
    public Result<MessagePageVO> listMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = messageRepository.findAssignmentChatByCursor(cid, cursor, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .build());
    }

    @PostMapping("/{cid}/assignment-chat/messages")
    public Result<AssignmentChatSendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody @Valid AssignmentChatSendMessageRequest req) {
        ensureLogin(clerkUserId);
        return Result.success(AssignmentChatSendMessageResponseVO.from(
                turnOrchestrator.startAssignmentChat(
                        clerkUserId, cid, req.getMessage(), req.getArtifactUids())));
    }

    @PostMapping("/{cid}/assignment-chat/sessions/{sid}/cancel")
    public Result<AssignmentChatSendMessageResponseVO> cancelSession(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @VerlaPublicId(VerlaPublicIdType.SESSION) @PathVariable Long sid) {
        ensureLogin(clerkUserId);
        return Result.success(AssignmentChatSendMessageResponseVO.from(
                turnOrchestrator.cancelAssignmentChat(clerkUserId, cid, sid)));
    }

    @PostMapping("/{cid}/assignment-chat/messages/{turnId}/retry")
    public Result<AssignmentChatSendMessageResponseVO> retryMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @VerlaPublicId(VerlaPublicIdType.TURN) @PathVariable Long turnId) {
        ensureLogin(clerkUserId);
        return Result.success(AssignmentChatSendMessageResponseVO.from(
                turnOrchestrator.retryAssignmentChat(clerkUserId, cid, turnId)));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
