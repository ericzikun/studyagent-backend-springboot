package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.MarkAnnouncementReadRequest;
import com.studyagent.api.dto.response.AnnouncementListResponse;
import com.studyagent.api.dto.response.MarkAnnouncementReadResponse;
import com.studyagent.api.dto.response.NotificationItemResponse;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/v1/announcement")
@RequiredArgsConstructor
public class MockAnnouncementController {

    private final MockAuthSupport mockAuthSupport;
    private final MockStateStore mockStateStore;

    @GetMapping("/list")
    public Result<AnnouncementListResponse> list(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        Set<String> readIds = mockStateStore.readAnnouncementIds(user.uid());
        List<NotificationItemResponse> notifications = mockStateStore.listAnnouncements().stream()
            .map(item -> NotificationItemResponse.builder()
                .id(item.publicId)
                .title(item.title)
                .message(item.message)
                .content(item.content)
                .createdAt(item.createdAtEpochSec)
                .isRead(readIds.contains(item.publicId))
                .build())
            .toList();

        return Result.success(AnnouncementListResponse.builder()
            .notifications(notifications)
            .build());
    }

    @PostMapping("/read")
    public Result<MarkAnnouncementReadResponse> markRead(
        @Valid @RequestBody MarkAnnouncementReadRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        List<String> updatedIds = mockStateStore.markAnnouncementsRead(user.uid(), request.getIds());
        return Result.success(MarkAnnouncementReadResponse.builder()
            .success(true)
            .updatedIds(updatedIds)
            .updatedAt(Instant.now().toString())
            .build());
    }
}
