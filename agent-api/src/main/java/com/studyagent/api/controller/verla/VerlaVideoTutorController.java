package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.request.VideoTutorTurnRequest;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.SendMessageResponseVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.dto.verla.response.VideoTutorSnapshotVO;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.VideoTutorSnapshotService;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Video Tutor：交互式白板教学（左栏对话 + 右栏白板）。
 * <p>
 * 历史隔离（{@code scene=VIDEO_TUTOR}）：左栏可见、可回看，但不进主对话线程。
 * <p>
 * <b>不做 legacy 会话兼容</b>：这是新产品，历史里不可能存在 legacy 形态的白板会话
 * （作业追问那边要兼容是因为它挂在既有 assignment 会话上）。少一个分支就少一处将来会腐的逻辑。
 * <p>
 * <b>没有 retry 端点</b>：白板一轮的产物是"板上现有什么"，重打同一轮会重复上板，v1 不提供。
 */
@RestController
@RequestMapping("/v1/verla/conversations")
@RequiredArgsConstructor
public class VerlaVideoTutorController {

    private final VerlaTurnOrchestrator turnOrchestrator;
    private final VerlaConversationService conversationService;
    private final VideoTutorSnapshotService snapshotService;
    private final VerlaMessageRepository messageRepository;

    /**
     * 发起一轮白板教学。返回本轮 turnId / userMessageId / agentSessionId。
     * <p>
     * {@code message} 可为空（首轮）；同一 conversation 上已有未完成的白板 turn 时
     * 返回 {@code ILLEGAL_STATE}（error data 带 {@code runningTurnId}）。
     */
    @PostMapping("/{cid}/video-tutor/messages")
    public Result<SendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody @Valid VideoTutorTurnRequest req) {
        ensureLogin(clerkUserId);
        return Result.success(SendMessageResponseVO.from(
                turnOrchestrator.startVideoTutorTurn(
                        clerkUserId, cid, req.getMessage(), req.getOutputLanguage(),
                        Boolean.TRUE.equals(req.getSoundEnabled()),
                        req.getTeachingStyle(),
                        Boolean.TRUE.equals(req.getStyleChanged()))));
    }

    /**
     * 刷新 / 重开恢复：一次拿到消息 + 板子 + SSE 续传游标。
     * <p>
     * artifact 优先，不重放事件（板子的唯一真相是会话级单行 artifact）。
     */
    @GetMapping("/{cid}/video-tutor/snapshot")
    public Result<VideoTutorSnapshotVO> snapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        return Result.success(VideoTutorSnapshotVO.from(snapshotService.getSnapshot(cid)));
    }

    /** 左栏历史向上翻页（快照只给最近 100 条）。 */
    @GetMapping("/{cid}/video-tutor/messages")
    public Result<MessagePageVO> listMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = messageRepository.findVideoTutorByCursor(cid, cursor, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).toList())
                .nextCursor(nextCursor)
                .build());
    }

    @PostMapping("/{cid}/video-tutor/sessions/{sid}/cancel")
    public Result<SendMessageResponseVO> cancelSession(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @VerlaPublicId(VerlaPublicIdType.SESSION) @PathVariable Long sid) {
        ensureLogin(clerkUserId);
        return Result.success(SendMessageResponseVO.from(
                turnOrchestrator.cancelVideoTutorTurn(clerkUserId, cid, sid)));
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
