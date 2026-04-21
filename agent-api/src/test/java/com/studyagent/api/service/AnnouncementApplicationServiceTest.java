package com.studyagent.api.service;

import com.studyagent.api.dto.response.AnnouncementListResponse;
import com.studyagent.api.dto.response.NotificationItemResponse;
import com.studyagent.infra.entity.AnnouncementEntity;
import com.studyagent.infra.mapper.AnnouncementMapper;
import com.studyagent.infra.mapper.UserAnnouncementReadMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnnouncementApplicationServiceTest {

    private final AnnouncementMapper announcementMapper = mock(AnnouncementMapper.class);
    private final UserAnnouncementReadMapper userAnnouncementReadMapper = mock(UserAnnouncementReadMapper.class);
    private final AnnouncementApplicationService service =
            new AnnouncementApplicationService(announcementMapper, userAnnouncementReadMapper);

    @Test
    void listPublicShouldMapAnnouncementsWithoutReadState() {
        AnnouncementEntity published = new AnnouncementEntity();
        published.setPublicId("announcement-1");
        published.setTitle("System update");
        published.setMessage("Available to every visitor");
        published.setContent("Detailed content");
        published.setPublishAt(LocalDateTime.of(2026, 4, 21, 10, 0));
        published.setCreatedAt(LocalDateTime.of(2026, 4, 20, 8, 0));

        when(announcementMapper.selectList(any())).thenReturn(List.of(published));

        AnnouncementListResponse response = service.listPublic();

        assertThat(response.getNotifications()).hasSize(1);
        NotificationItemResponse item = response.getNotifications().get(0);
        assertThat(item.getId()).isEqualTo("announcement-1");
        assertThat(item.getTitle()).isEqualTo("System update");
        assertThat(item.getMessage()).isEqualTo("Available to every visitor");
        assertThat(item.getContent()).isEqualTo("Detailed content");
        assertThat(item.getCreatedAt()).isGreaterThan(0L);
        assertThat(item.getIsRead()).isNull();
        verifyNoInteractions(userAnnouncementReadMapper);
    }

    @Test
    void listPublicShouldReturnEmptyNotificationsWhenNoAnnouncements() {
        when(announcementMapper.selectList(any())).thenReturn(List.of());

        AnnouncementListResponse response = service.listPublic();

        assertThat(response.getNotifications()).isEmpty();
        verifyNoInteractions(userAnnouncementReadMapper);
    }
}
