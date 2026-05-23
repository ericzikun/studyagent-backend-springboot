package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.FileChatPanelView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatPanelResponseVO {

    private FileChatPanelFileVO file;
    private FileChatPanelAnalysisVO analysis;
    private List<String> suggestedQuestions;
    private List<FileChatMessageVO> messages;
    private Long nextCursor;

    public static FileChatPanelResponseVO from(FileChatPanelView view) {
        if (view == null) {
            return null;
        }
        return FileChatPanelResponseVO.builder()
                .file(FileChatPanelFileVO.from(view.getFile()))
                .analysis(FileChatPanelAnalysisVO.from(view.getAnalysis()))
                .suggestedQuestions(view.getSuggestedQuestions() == null ? List.of() : view.getSuggestedQuestions())
                .messages(view.getMessages() == null
                        ? List.of()
                        : view.getMessages().stream().map(FileChatMessageVO::from).toList())
                .nextCursor(view.getNextCursor())
                .build();
    }
}
