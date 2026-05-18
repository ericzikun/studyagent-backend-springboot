package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.PlanConfirmResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanConfirmResponseVO {

    private boolean success;
    private String nextStage;
    private String redirectUrl;
    private SendMessageResponseVO message;

    public static PlanConfirmResponseVO from(PlanConfirmResult r) {
        return PlanConfirmResponseVO.builder()
                .success(r.isSuccess())
                .nextStage(r.getNextStage())
                .redirectUrl(r.getRedirectUrl())
                .message(r.getMessageResult() == null ? null : SendMessageResponseVO.from(r.getMessageResult()))
                .build();
    }
}
