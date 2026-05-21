package com.studyagent.api.dto.verla.response;

import com.studyagent.service.application.verla.dto.FileChatPanelFileView;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatPanelFileVO {

    private String objectId;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    private String extractStatus;

    public static FileChatPanelFileVO from(FileChatPanelFileView view) {
        if (view == null) {
            return null;
        }
        return FileChatPanelFileVO.builder()
                .objectId(view.getObjectId())
                .name(view.getName())
                .mimeType(view.getMimeType())
                .sizeBytes(view.getSizeBytes())
                .extractStatus(view.getExtractStatus())
                .build();
    }
}
