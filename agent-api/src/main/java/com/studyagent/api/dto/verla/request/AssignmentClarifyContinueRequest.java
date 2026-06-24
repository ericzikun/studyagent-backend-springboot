package com.studyagent.api.dto.verla.request;

import com.studyagent.api.jackson.verla.VerlaPublicIdField;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AssignmentClarifyContinueRequest {

    @VerlaPublicIdField(VerlaPublicIdType.SESSION)
    private Long sessionId;
    private String userChoice;
    private Boolean userUnderstood;
    private String text;
    private List<String> objectIds;
    private String formId;
    private String title;
    private Map<String, Object> reservedFields;
    private List<Map<String, Object>> appendAskAnswers;
    private Map<String, Object> requirementForm;
}
