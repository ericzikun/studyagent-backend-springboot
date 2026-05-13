package com.studyagent.api.dto.verla.request;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AssignmentClarifyContinueRequest {

    private Long sessionId;
    private String userChoice;
    private Boolean userUnderstood;
    private String text;
    private List<String> objectIds;
    private Map<String, Object> reservedFields;
    private List<Map<String, Object>> appendAskAnswers;
    private Map<String, Object> requirementForm;
}
