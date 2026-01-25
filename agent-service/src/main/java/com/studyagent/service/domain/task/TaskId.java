package com.studyagent.service.domain.task;

import lombok.Value;

/**
 * 任务ID值对象
 */
@Value
public class TaskId {
    Long value;
    
    public static TaskId of(Long value) {
        return new TaskId(value);
    }
}

