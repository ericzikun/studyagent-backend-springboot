package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.MarkAnnouncementReadRequest;
import com.studyagent.api.dto.response.AnnouncementListResponse;
import com.studyagent.api.dto.response.MarkAnnouncementReadResponse;
import com.studyagent.api.service.AnnouncementApplicationService;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 公告/通知：内容来自 announcements 表配置，已读状态按用户持久化。
 * <p>
 * GET  /v1/announcement/list  — 当前用户通知列表
 * POST /v1/announcement/read   — 标记已读（body: ids）
 */
@RestController
@RequestMapping("/v1/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementApplicationService announcementApplicationService;

    @GetMapping("/list")
    public Result<AnnouncementListResponse> list(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        AnnouncementListResponse data = announcementApplicationService.listForUser(clerkUserId);
        return Result.success(data);
    }

    @PostMapping("/read")
    public Result<MarkAnnouncementReadResponse> markRead(
            @Valid @RequestBody MarkAnnouncementReadRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        MarkAnnouncementReadResponse data = announcementApplicationService.markRead(clerkUserId, request.getIds());
        return Result.success(data);
    }
}
