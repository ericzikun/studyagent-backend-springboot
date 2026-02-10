package com.studyagent.api.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 批量逻辑删除任务响应
 */
@Data
@Builder
public class DeleteTasksResponse {
    /** 成功删除的数量 */
    private int deletedCount;
    /** 删除失败的任务ID列表（不存在或无权限） */
    private List<Long> failedTaskIds;
}
