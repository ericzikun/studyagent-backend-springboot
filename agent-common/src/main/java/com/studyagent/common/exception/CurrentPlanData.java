package com.studyagent.common.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentPlanData {

    private String planCode;
    private String tier;
    private Boolean isPaid;
}
