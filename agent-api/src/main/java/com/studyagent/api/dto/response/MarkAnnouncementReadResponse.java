package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkAnnouncementReadResponse {

    private boolean success;

    @Builder.Default
    private List<String> updatedIds = new ArrayList<>();

    /** ISO-8601 UTC，如 2026-03-24T10:30:00Z */
    private String updatedAt;
}
