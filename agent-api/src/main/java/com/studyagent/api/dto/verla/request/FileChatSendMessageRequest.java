package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileChatSendMessageRequest {

    @NotBlank
    private String objectId;

    @NotBlank
    private String message;
}
