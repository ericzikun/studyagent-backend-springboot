package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChatMessageMeta {

    public static final String SCENE_FILE_CHAT = "FILE_CHAT";
    public static final String SCENE_ASSIGNMENT_CHAT = "ASSIGNMENT_CHAT";

    private String scene;
    private String objectId;
}
