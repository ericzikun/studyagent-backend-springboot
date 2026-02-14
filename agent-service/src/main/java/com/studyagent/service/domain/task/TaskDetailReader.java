package com.studyagent.service.domain.task;

import com.studyagent.service.application.dto.TaskDetailDTO;

import java.util.Optional;

/**
 * 任务详情查询接口（只读）
 * 负责从数据库组装任务详情数据
 */
public interface TaskDetailReader {

    /**
     * 根据任务ID加载任务详情
     * @param taskId 任务ID
     * @return 任务详情，不存在或已删除时返回 Optional.empty()
     */
    Optional<TaskDetailDTO> loadByTaskId(Long taskId);
}
