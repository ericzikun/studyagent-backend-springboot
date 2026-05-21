package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatPanelState {

    private FileChatAnalysisState analysis;
    private List<String> suggestedQuestions;
    private String updatedAt;
}
