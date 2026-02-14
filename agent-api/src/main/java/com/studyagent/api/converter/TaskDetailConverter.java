package com.studyagent.api.converter;

import com.studyagent.api.dto.response.TaskDetailResponse;
import com.studyagent.service.application.dto.TaskDetailDTO;

import java.util.Collections;
import java.util.stream.Collectors;

/**
 * 任务详情 DTO 与 API Response 转换器
 */
public final class TaskDetailConverter {

    private TaskDetailConverter() {}

    public static TaskDetailResponse toResponse(TaskDetailDTO dto) {
        if (dto == null) return null;

        return TaskDetailResponse.builder()
                .taskBaseInfo(toBaseInfoResponse(dto.getTaskBaseInfo()))
                .agentInfoList(dto.getAgentInfoList() != null
                        ? dto.getAgentInfoList().stream().map(TaskDetailConverter::toAgentInfoResponse).collect(Collectors.toList())
                        : Collections.emptyList())
                .subTaskInfoList(dto.getSubTaskInfoList() != null
                        ? dto.getSubTaskInfoList().stream().map(TaskDetailConverter::toSubTaskInfoResponse).collect(Collectors.toList())
                        : Collections.emptyList())
                .activityInfoList(dto.getActivityInfoList() != null
                        ? dto.getActivityInfoList().stream().map(TaskDetailConverter::toActivityInfoResponse).collect(Collectors.toList())
                        : Collections.emptyList())
                .outputSummaryInfo(toOutputInfoResponse(dto.getOutputSummaryInfo()))
                .outputDetailInfoList(dto.getOutputDetailInfoList() != null
                        ? dto.getOutputDetailInfoList().stream().map(TaskDetailConverter::toOutputInfoResponse).collect(Collectors.toList())
                        : Collections.emptyList())
                .uploadedFileInfoList(dto.getUploadedFileInfoList() != null
                        ? dto.getUploadedFileInfoList().stream().map(TaskDetailConverter::toUploadedFileInfoResponse).collect(Collectors.toList())
                        : Collections.emptyList())
                .build();
    }

    private static TaskDetailResponse.TaskBaseInfoResponse toBaseInfoResponse(TaskDetailDTO.TaskBaseInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.TaskBaseInfoResponse.builder()
                .taskTitle(info.getTaskTitle())
                .taskDesc(info.getTaskDesc())
                .taskStatus(info.getTaskStatus())
                .startTime(info.getStartTime())
                .dueTime(info.getDueTime())
                .finishTime(info.getFinishTime())
                .costTime(info.getCostTime())
                .subject(info.getSubject())
                .academicLevel(info.getAcademicLevel())
                .priorityLevel(info.getPriorityLevel())
                .citationStyle(info.getCitationStyle())
                .pageLength(info.getPageLength())
                .formatList(info.getFormatList())
                .specialInstructions(info.getSpecialInstructions())
                .completePercent(info.getCompletePercent())
                .taskCompletedSize(info.getTaskCompletedSize())
                .activeAgentSize(info.getActiveAgentSize())
                .estRemainingTime(info.getEstRemainingTime())
                .queueAheadCount(info.getQueueAheadCount())
                .requirementJson(info.getRequirementJson())
                .build();
    }

    private static TaskDetailResponse.AgentInfoResponse toAgentInfoResponse(TaskDetailDTO.AgentInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.AgentInfoResponse.builder()
                .agentName(info.getAgentName())
                .subtaskId(info.getSubtaskId())
                .subtaskTitle(info.getSubtaskTitle())
                .agentStatus(info.getAgentStatus())
                .completePercent(info.getCompletePercent())
                .agentDesc(info.getAgentDesc())
                .agentStartTime(info.getAgentStartTime())
                .agentFinishTime(info.getAgentFinishTime())
                .agentPriority(info.getAgentPriority())
                .agentOutput(info.getAgentOutput())
                .build();
    }

    private static TaskDetailResponse.SubTaskInfoResponse toSubTaskInfoResponse(TaskDetailDTO.SubTaskInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.SubTaskInfoResponse.builder()
                .title(info.getTitle())
                .desc(info.getDesc())
                .processDesc(info.getProcessDesc())
                .agentName(info.getAgentName())
                .subtaskCode(info.getSubtaskCode())
                .agentStatus(info.getAgentStatus())
                .agentCompletePercent(info.getAgentCompletePercent())
                .agentDesc(info.getAgentDesc())
                .agentStartTime(info.getAgentStartTime())
                .agentFinishTime(info.getAgentFinishTime())
                .agentPriority(info.getAgentPriority())
                .agentOutput(info.getAgentOutput())
                .build();
    }

    private static TaskDetailResponse.ActivityInfoResponse toActivityInfoResponse(TaskDetailDTO.ActivityInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.ActivityInfoResponse.builder()
                .activityTime(info.getActivityTime())
                .agentName(info.getAgentName())
                .activityDesc(info.getActivityDesc())
                .build();
    }

    private static TaskDetailResponse.OutputInfoResponse toOutputInfoResponse(TaskDetailDTO.OutputInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.OutputInfoResponse.builder()
                .title(info.getTitle())
                .desc(info.getDesc())
                .url(info.getUrl())
                .sizeDesc(info.getSizeDesc())
                .pageSize(info.getPageSize())
                .format(info.getFormat())
                .outputType(info.getOutputType())
                .build();
    }

    private static TaskDetailResponse.UploadedFileInfoResponse toUploadedFileInfoResponse(TaskDetailDTO.UploadedFileInfo info) {
        if (info == null) return null;
        return TaskDetailResponse.UploadedFileInfoResponse.builder()
                .objectId(info.getObjectId())
                .fileName(info.getFileName())
                .fileType(info.getFileType())
                .fileSize(info.getFileSize())
                .uploadTime(info.getUploadTime())
                .downloadUrl(info.getDownloadUrl())
                .build();
    }
}
