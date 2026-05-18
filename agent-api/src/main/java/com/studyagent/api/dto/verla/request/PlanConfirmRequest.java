package com.studyagent.api.dto.verla.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlanConfirmRequest {

    @NotNull
    private Boolean confirmed;

    private String somethingElseText;
}
