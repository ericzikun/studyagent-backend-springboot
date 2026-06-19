package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload returned when a paid action is blocked until the user completes checkout.
 *
 * scene:
 * - upload: Verla attachment upload hit a plan file-limit gate and should resume with resumeToken after payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResumeRequiredResponse {

    private String scene;
    private String resumeToken;
}
