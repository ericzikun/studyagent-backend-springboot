package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.MarkAnnouncementReadRequest;
import com.studyagent.api.dto.response.AnnouncementListResponse;
import com.studyagent.api.dto.response.MarkAnnouncementReadResponse;
import com.studyagent.api.dto.response.NotificationItemResponse;
import com.studyagent.api.service.AnnouncementApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnouncementControllerTest {

    private final AnnouncementApplicationService announcementApplicationService = mock(AnnouncementApplicationService.class);
    private final AnnouncementController announcementController = new AnnouncementController(announcementApplicationService);

    @Test
    void listShouldReturnPublicAnnouncementsWithoutUserContext() {
        AnnouncementListResponse payload = AnnouncementListResponse.builder()
                .notifications(List.of(
                        NotificationItemResponse.builder()
                                .id("announcement-1")
                                .title("System update")
                                .message("Public banner")
                                .createdAt(1710000000L)
                                .build()
                ))
                .build();
        when(announcementApplicationService.listPublic()).thenReturn(payload);

        Result<AnnouncementListResponse> result = announcementController.list();

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(result.getData().getNotifications()).hasSize(1);
        assertThat(result.getData().getNotifications().get(0).getId()).isEqualTo("announcement-1");
    }

    @Test
    void markReadShouldStillRequireSignedInUser() {
        MarkAnnouncementReadRequest request = new MarkAnnouncementReadRequest();
        request.setIds(List.of("announcement-1"));

        Result<MarkAnnouncementReadResponse> result = announcementController.markRead(request, null);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(401);
    }
}
