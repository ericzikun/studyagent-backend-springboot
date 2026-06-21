package com.studyagent.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommercialBlockData {

    private CurrentPlanData currentPlan;
    private String reasonCode;
    private String purchaseProductId;
    private String blockedAction;

    private List<String> allowedOutputTypes;
    private List<String> requestedOutputTypes;
    private List<String> unsupportedOutputTypes;

    private String scene;
    private String resumeToken;

    private Integer maxFiles;
    private Long activeFiles;

    private Integer maxFollowupEdits;
    private Long usedFollowupEdits;
}
