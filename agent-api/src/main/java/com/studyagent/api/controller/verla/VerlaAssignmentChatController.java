package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.AssignmentChatSendMessageRequest;
import com.studyagent.api.dto.verla.response.AssignmentChatSendMessageResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/{cid}/assignment-chat/messages")
    public Result<AssignmentChatSendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @RequestBody @Valid AssignmentChatSendMessageRequest req) {
        ensureLogin(clerkUserId);
        return Result.success(AssignmentChatSendMessageResponseVO.from(
                turnOrchestrator.startAssignmentChat(
                        clerkUserId, cid, req.getMessage(), req.getArtifactUids())));
    }

    @PostMapping("/{cid}/assignment-chat/sessions/{sid}/cancel")
    public Result<AssignmentChatSendMessageResponseVO> cancelSession(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @PathVariable Long sid) {
        ensureLogin(clerkUserId);
        return Result.success(AssignmentChatSendMessageResponseVO.from(
                turnOrchestrator.cancelAssignmentChat(clerkUserId, cid, sid)));
    }

    @PostMapping("/{cid}/assignment-chat/messages/{turnId}/retry")
    public Result<AssignmentChatSendMessageResponseVO> retryMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid,
            @PathVariable Long turnId) {
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
