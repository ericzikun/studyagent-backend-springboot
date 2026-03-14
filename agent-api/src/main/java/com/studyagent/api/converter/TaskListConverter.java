package com.studyagent.api.converter;

import com.studyagent.api.dto.response.TaskListItemResponse;
import com.studyagent.api.dto.response.TaskListResponse;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.service.application.dto.TaskListItemDTO;
import com.studyagent.service.application.dto.TaskListResult;

import java.util.Collections;
import java.util.stream.Collectors;

/**
 * 任务列表 DTO 与 API Response 转换器
 */
public final class TaskListConverter {

    private TaskListConverter() {}

    public static TaskListResponse toResponse(TaskListResult result) {
        if (result == null) return null;
        return TaskListResponse.builder()
                .taskList(result.getTaskList() != null
                        ? result.getTaskList().stream()
                                .map(TaskListConverter::toItemResponse)
                                .collect(Collectors.toList())
                        : Collections.emptyList())
                .total(result.getTotal())
                .pageNo(result.getPageNo())
                .pageSize(result.getPageSize())
                .build();
    }

    private static TaskListItemResponse toItemResponse(TaskListItemDTO dto) {
        if (dto == null) return null;
        String encodedId = dto.getId() != null ? TaskIdEncoder.encode(dto.getId().getValue()) : null;
        TaskListItemResponse.IdValue idValue = encodedId != null
                ? TaskListItemResponse.IdValue.builder().value(encodedId).build()
                : null;
        return TaskListItemResponse.builder()
                .id(idValue)
                .clerkUserId(dto.getClerkUserId())
                .taskTitle(dto.getTaskTitle())
                .taskDesc(dto.getTaskDesc())
                .subject(dto.getSubject())
                .academicLevel(dto.getAcademicLevel())
                .priorityLevel(dto.getPriorityLevel())
                .dueDate(dto.getDueDate())
                .format(dto.getFormat())
                .citationStyle(dto.getCitationStyle())
                .pageLength(dto.getPageLength())
                .specialInstructions(dto.getSpecialInstructions())
                .status(dto.getStatus())
                .startTime(dto.getStartTime())
                .finishTime(dto.getFinishTime())
                .costTime(dto.getCostTime())
                .completePercent(dto.getCompletePercent())
                .taskCompletedSize(dto.getTaskCompletedSize())
                .activeAgentSize(dto.getActiveAgentSize())
                .estRemainingTime(dto.getEstRemainingTime())
                .requirementJson(dto.getRequirementJson())
                .finalResult(dto.getFinalResult())
                .errorMessage(dto.getErrorMessage())
                .queueAheadCount(dto.getQueueAheadCount())
                .build();
    }
}
