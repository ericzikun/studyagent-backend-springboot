package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyEventResponse {

    private String eventId;
    private String sourceService;
    private String scene;
    private String level;
    private String contentType;
    private String env;
    private String status;
    private String deliveryId;
    private NotifyErrorResponse error;
}
