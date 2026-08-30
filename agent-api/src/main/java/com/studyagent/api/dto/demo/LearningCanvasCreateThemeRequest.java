package com.studyagent.api.dto.demo;

import lombok.Data;

/**
 * Learning Canvas 创建主题请求
 */
@Data
public class LearningCanvasCreateThemeRequest {

    /** 用户开场 query */
    private String initialQuery;

    /** 人格：sheldon（傲娇学神）/ lasso（治愈教练），默认 sheldon */
    private String persona;
}
