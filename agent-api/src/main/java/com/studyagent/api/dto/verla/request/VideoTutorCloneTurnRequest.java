package com.studyagent.api.dto.verla.request;

import lombok.Data;

/** clone 模式的一轮请求；文本为空表示“开始吧”。 */
@Data
public class VideoTutorCloneTurnRequest {

    private String message;

    private String language;

    private String teachingStyle;
}
