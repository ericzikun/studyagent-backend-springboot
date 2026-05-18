package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanConfirmResult {

    private boolean success;
    private String nextStage;
    private String redirectUrl;
    private SendMessageResult messageResult;
}
