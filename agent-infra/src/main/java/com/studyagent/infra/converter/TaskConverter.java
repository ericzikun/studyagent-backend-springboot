package com.studyagent.infra.converter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.service.domain.task.Task;
import com.studyagent.service.domain.task.TaskId;
import com.studyagent.service.domain.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Task Entity 和 Domain Model 转换器
 */
@Component
public class TaskConverter {
    
    private final Gson gson = new Gson();
    private final Type formatListType = new TypeToken<List<Integer>>(){}.getType();
    
    public Task toDomain(TaskEntity entity) {
        if (entity == null) {
            return null;
        }
        
        List<Integer> formatList = new ArrayList<>();
        if (entity.getFormat() != null && !entity.getFormat().isEmpty()) {
            try {
                formatList = gson.fromJson(entity.getFormat(), formatListType);
            } catch (Exception e) {
                // 如果解析失败，使用空列表
            }
        }
        
        return Task.builder()
            .id(TaskId.of(entity.getId()))
            .clerkUserId(entity.getClerkUserId())
            .taskTitle(entity.getTaskTitle())
            .taskDesc(entity.getTaskDesc())
            .subject(entity.getSubject())
            .academicLevel(entity.getAcademicLevel())
            .priorityLevel(entity.getPriorityLevel())
            .dueDate(entity.getDueDate())
            .format(formatList)
            .citationStyle(entity.getCitationStyle())
            .pageLength(entity.getPageLength())
            .specialInstructions(entity.getSpecialInstructions())
            .status(TaskStatus.fromCode(entity.getStatus()))
            .startTime(entity.getStartTime())
            .finishTime(entity.getFinishTime())
            .costTime(entity.getCostTime())
            .completePercent(entity.getCompletePercent())
            .taskCompletedSize(entity.getTaskCompletedSize())
            .activeAgentSize(entity.getActiveAgentSize())
            .estRemainingTime(entity.getEstRemainingTime())
            .requirementJson(entity.getRequirementJson())
            .finalResult(entity.getFinalResult())
            .errorMessage(entity.getErrorMessage())
            .traceId(entity.getTraceId())
            .build();
    }
    
    public TaskEntity toEntity(Task domain) {
        if (domain == null) {
            return null;
        }
        
        TaskEntity entity = new TaskEntity();
        entity.setId(domain.getId() != null ? domain.getId().getValue() : null);
        entity.setClerkUserId(domain.getClerkUserId());
        entity.setTaskTitle(domain.getTaskTitle());
        entity.setTaskDesc(domain.getTaskDesc());
        entity.setSubject(domain.getSubject());
        entity.setAcademicLevel(domain.getAcademicLevel());
        entity.setPriorityLevel(domain.getPriorityLevel());
        entity.setDueDate(domain.getDueDate());
        entity.setFormat(domain.getFormat() != null ? gson.toJson(domain.getFormat()) : "[]");
        entity.setCitationStyle(domain.getCitationStyle());
        entity.setPageLength(domain.getPageLength());
        entity.setSpecialInstructions(domain.getSpecialInstructions());
        entity.setStatus(domain.getStatus() != null ? domain.getStatus().getCode() : TaskStatus.DRAFT.getCode());
        entity.setStartTime(domain.getStartTime());
        entity.setFinishTime(domain.getFinishTime());
        entity.setCostTime(domain.getCostTime());
        entity.setCompletePercent(domain.getCompletePercent());
        entity.setTaskCompletedSize(domain.getTaskCompletedSize());
        entity.setActiveAgentSize(domain.getActiveAgentSize());
        entity.setEstRemainingTime(domain.getEstRemainingTime());
        entity.setRequirementJson(domain.getRequirementJson());
        entity.setFinalResult(domain.getFinalResult());
        entity.setErrorMessage(domain.getErrorMessage());
        entity.setTraceId(domain.getTraceId());
        
        return entity;
    }
}

