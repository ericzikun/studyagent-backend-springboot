package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatAnalysisState {

    private FileChatAnalysisStatus status;
    private String text;

    public static FileChatAnalysisState pending() {
        return FileChatAnalysisState.builder()
                .status(FileChatAnalysisStatus.PENDING)
                .text("")
                .build();
    }
}
