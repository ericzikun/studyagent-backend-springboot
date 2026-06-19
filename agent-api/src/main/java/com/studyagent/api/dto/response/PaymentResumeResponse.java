package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.studyagent.api.dto.verla.response.VerlaUploadSignResponseVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResumeResponse {

    private String scene;
    private String status;
    private HumanizerTaskResponse task;
    private VerlaUploadSignResponseVO uploadSign;
}
