package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatPanelAnalysisVO {

    private String status;
    private String text;

    public static FileChatPanelAnalysisVO from(FileChatAnalysisState state) {
        if (state == null) {
            return FileChatPanelAnalysisVO.builder()
                    .status("PENDING")
                    .text("")
                    .build();
        }
        return FileChatPanelAnalysisVO.builder()
                .status(state.getStatus() == null ? "PENDING" : state.getStatus().name())
                .text(state.getText() == null ? "" : state.getText())
                .build();
    }
}
