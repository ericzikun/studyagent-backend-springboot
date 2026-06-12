package com.studyagent.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.dto.response.AnnouncementListResponse;
import com.studyagent.api.dto.response.MarkAnnouncementReadResponse;
import com.studyagent.api.dto.response.NotificationItemResponse;
import com.studyagent.infra.entity.AnnouncementEntity;
import com.studyagent.infra.entity.UserAnnouncementReadEntity;
import com.studyagent.infra.mapper.AnnouncementMapper;
import com.studyagent.infra.mapper.UserAnnouncementReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studyagent.common.datetime.DateTimeFormats;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnnouncementApplicationService {

    private static final DateTimeFormatter ISO_INSTANT_FMT = DateTimeFormatter.ISO_INSTANT;

    private final AnnouncementMapper announcementMapper;
    private final UserAnnouncementReadMapper userAnnouncementReadMapper;

    public AnnouncementListResponse listForUser(String clerkUserId) {
        LocalDateTime now = LocalDateTime.now();
        List<AnnouncementEntity> announcements = announcementMapper.selectList(
                new LambdaQueryWrapper<AnnouncementEntity>()
                        .eq(AnnouncementEntity::getIsActive, true)
                        .and(w -> w.isNull(AnnouncementEntity::getPublishAt)
                                .or()
                                .le(AnnouncementEntity::getPublishAt, now))
                        .and(w -> w.isNull(AnnouncementEntity::getExpireAt)
                                .or()
                                .gt(AnnouncementEntity::getExpireAt, now))
                        .orderByDesc(AnnouncementEntity::getSortOrder)
                        .orderByDesc(AnnouncementEntity::getId));

        if (announcements.isEmpty()) {
            return AnnouncementListResponse.builder().notifications(List.of()).build();
        }

        List<String> publicIds = announcements.stream()
                .map(AnnouncementEntity::getPublicId)
                .collect(Collectors.toList());

        Set<String> readIds = new HashSet<>();
        if (!publicIds.isEmpty()) {
            List<UserAnnouncementReadEntity> reads = userAnnouncementReadMapper.selectList(
                    new LambdaQueryWrapper<UserAnnouncementReadEntity>()
                            .eq(UserAnnouncementReadEntity::getClerkUserId, clerkUserId)
                            .in(UserAnnouncementReadEntity::getAnnouncementPublicId, publicIds));
            for (UserAnnouncementReadEntity r : reads) {
                readIds.add(r.getAnnouncementPublicId());
            }
        }

        List<NotificationItemResponse> items = new ArrayList<>(announcements.size());
        for (AnnouncementEntity a : announcements) {
            LocalDateTime createdBase = a.getPublishAt() != null ? a.getPublishAt() : a.getCreatedAt();
            items.add(NotificationItemResponse.builder()
                    .id(a.getPublicId())
                    .title(a.getTitle())
                    .message(a.getMessage())
                    .content(a.getContent())
                    .createdAt(localDateTimeToEpochSeconds(createdBase))
                    .isRead(readIds.contains(a.getPublicId()))
                    .build());
        }

        return AnnouncementListResponse.builder().notifications(items).build();
    }

    @Transactional
    public MarkAnnouncementReadResponse markRead(String clerkUserId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return MarkAnnouncementReadResponse.builder()
                    .success(true)
                    .updatedIds(List.of())
                    .updatedAt(ISO_INSTANT_FMT.format(Instant.now()))
                    .build();
        }

        List<String> distinctIds = ids.stream().distinct().collect(Collectors.toList());

        List<AnnouncementEntity> existing = announcementMapper.selectList(
                new LambdaQueryWrapper<AnnouncementEntity>()
                        .in(AnnouncementEntity::getPublicId, distinctIds));
        Set<String> valid = existing.stream()
                .map(AnnouncementEntity::getPublicId)
                .collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now();
        List<String> updated = new ArrayList<>();

        for (String publicId : distinctIds) {
            if (!valid.contains(publicId)) {
                continue;
            }
            UserAnnouncementReadEntity row = userAnnouncementReadMapper.selectOne(
                    new LambdaQueryWrapper<UserAnnouncementReadEntity>()
                            .eq(UserAnnouncementReadEntity::getClerkUserId, clerkUserId)
                            .eq(UserAnnouncementReadEntity::getAnnouncementPublicId, publicId));

            if (row == null) {
                UserAnnouncementReadEntity insert = new UserAnnouncementReadEntity();
                insert.setClerkUserId(clerkUserId);
                insert.setAnnouncementPublicId(publicId);
                insert.setReadAt(now);
                userAnnouncementReadMapper.insert(insert);
            } else {
                row.setReadAt(now);
                userAnnouncementReadMapper.updateById(row);
            }
            updated.add(publicId);
        }

        return MarkAnnouncementReadResponse.builder()
                .success(true)
                .updatedIds(updated)
                .updatedAt(ISO_INSTANT_FMT.format(Instant.now()))
                .build();
    }

    private static long localDateTimeToEpochSeconds(LocalDateTime dt) {
        return DateTimeFormats.toEpochSecond(dt);
    }
}
