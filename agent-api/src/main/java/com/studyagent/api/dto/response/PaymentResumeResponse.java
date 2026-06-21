package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.studyagent.api.dto.verla.response.VerlaUploadSignResponseVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a one-time payment resume token is consumed.
 *
 * scene:
 * - humanizer_start: resume a humanizer task launch blocked by quota
 * - detection_start: resume a detection task launch blocked by quota
 * - upload: reissue a fresh Verla upload sign blocked by file-limit
 *
 * status:
 * - resumed: the blocked action has been resumed successfully
 */
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
