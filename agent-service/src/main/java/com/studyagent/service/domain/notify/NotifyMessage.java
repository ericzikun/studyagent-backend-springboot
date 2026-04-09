package com.studyagent.service.domain.notify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMessage {

    private String eventId;
    private String sourceService;
    private String scene;
    private String target;
    private String title;
    private String content;
    private String level;
    private String contentType;
    private String env;
    private String timestamp;
    private Map<String, Object> metadata;
}
