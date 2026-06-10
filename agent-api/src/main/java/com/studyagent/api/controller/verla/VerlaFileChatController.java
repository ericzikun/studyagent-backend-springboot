package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.FileChatSendMessageRequest;
import com.studyagent.api.dto.verla.response.FileChatPanelResponseVO;
import com.studyagent.api.dto.verla.response.FileChatSendMessageResponseVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaFileChatService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/verla/conversations")
@RequiredArgsConstructor
public class VerlaFileChatController {

    private final VerlaFileChatService fileChatService;
    private final VerlaTurnOrchestrator turnOrchestrator;

    @GetMapping("/{cid}/file-chat")
    public Result<FileChatPanelResponseVO> getPanel(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        ensureLogin(clerkUserId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return Result.success(FileChatPanelResponseVO.from(
                fileChatService.getPanel(clerkUserId, cid, objectId, cursor, safeLimit)));
    }

    @PostMapping("/{cid}/file-chat/messages")
    public Result<FileChatSendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody @Valid FileChatSendMessageRequest req) {
        ensureLogin(clerkUserId);
        return Result.success(FileChatSendMessageResponseVO.from(
                turnOrchestrator.startFileChat(clerkUserId, cid, req.getObjectId(), req.getMessage())));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
