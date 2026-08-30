package com.studyagent.api.dto.demo;

import lombok.Data;

/**
 * Learning Canvas 掌握度校准请求
 */
@Data
public class LearningCanvasMasteryRequest {

    /** 生疏 / 理解 / 熟练 */
    private String masteryLevel;
}
