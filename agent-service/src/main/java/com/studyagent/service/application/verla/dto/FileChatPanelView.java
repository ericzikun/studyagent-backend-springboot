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
public class FileChatPanelView {

    private FileChatPanelFileView file;
    private FileChatAnalysisState analysis;
    private List<String> suggestedQuestions;
    private List<FileChatPanelMessageView> messages;
    private Long nextCursor;
}
