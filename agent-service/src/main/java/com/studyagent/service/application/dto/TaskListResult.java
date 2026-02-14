package com.studyagent.service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务列表查询结果（应用层）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskListResult {
    private List<TaskListItemDTO> taskList;
    private Integer total;
    private Integer pageNo;
    private Integer pageSize;
}
