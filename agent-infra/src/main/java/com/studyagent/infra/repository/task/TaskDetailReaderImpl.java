package com.studyagent.infra.repository.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.service.application.dto.TaskDetailDTO;
import com.studyagent.service.domain.task.TaskDetailReader;
import com.studyagent.service.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.lang.reflect.Type;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务详情查询实现
 * 从数据库组装任务详情数据
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskDetailReaderImpl implements TaskDetailReader {

    private static final int TOTAL_ESTIMATED_SECONDS = 20 * 60;
    private static final int ACTIVITY_LIMIT = 10;
    private static final int SUBTASK_STATUS_COMPLETED = 2;

    private final com.studyagent.infra.mapper.TaskMapper taskMapper;
    private final TaskAgentMapper taskAgentMapper;
    private final SubTaskMapper subTaskMapper;
    private final TaskActivityMapper taskActivityMapper;
    private final TaskOutputMapper taskOutputMapper;
    private final TaskFileMapper taskFileMapper;
    private final FileMapper fileMapper;

    private final Gson gson = new Gson();
    private final Type formatListType = new TypeToken<List<Integer>>(){}.getType();

    @Override
    public Optional<TaskDetailDTO> loadByTaskId(Long taskId) {
        TaskEntity taskEntity = taskMapper.selectById(taskId);
        if (taskEntity == null || isDeleted(taskEntity)) {
            return Optional.empty();
        }

        TaskDetailDTO.TaskBaseInfo taskBaseInfo = buildTaskBaseInfo(taskEntity);
        List<SubTaskEntity> subTaskEntities = loadSubTasks(taskId);
        List<TaskAgentEntity> agentEntities = loadAgents(taskId);

        int completedSubtaskCount = (int) subTaskEntities.stream()
                .filter(st -> st.getStatus() != null && st.getStatus() == SUBTASK_STATUS_COMPLETED)
                .count();
        taskBaseInfo.setTaskCompletedSize(completedSubtaskCount);
        taskBaseInfo.setActiveAgentSize(subTaskEntities.size());

        Map<String, TaskAgentEntity> subtaskCodeToAgent = buildSubtaskToAgentMap(agentEntities);
        Map<String, TaskAgentEntity> agentNameToAgent = buildAgentNameToAgentMap(agentEntities);

        List<TaskDetailDTO.SubTaskInfo> subTaskInfoList = buildSubTaskInfoList(subTaskEntities, subtaskCodeToAgent, agentNameToAgent);
        List<TaskDetailDTO.ActivityInfo> activityInfoList = loadAndBuildActivities(taskId);
        List<TaskDetailDTO.AgentInfo> agentInfoList = buildAgentInfoList(agentEntities, subTaskEntities);
        List<TaskDetailDTO.OutputInfo> outputDetailInfoList = loadAndBuildOutputs(taskId);
        TaskDetailDTO.OutputInfo outputSummaryInfo = extractMainOutput(taskId);
        List<TaskDetailDTO.UploadedFileInfo> uploadedFileInfoList = loadUploadedFiles(taskId);

        TaskDetailDTO dto = TaskDetailDTO.builder()
                .taskBaseInfo(taskBaseInfo)
                .agentInfoList(agentInfoList)
                .subTaskInfoList(subTaskInfoList)
                .activityInfoList(activityInfoList)
                .outputSummaryInfo(outputSummaryInfo)
                .outputDetailInfoList(outputDetailInfoList)
                .uploadedFileInfoList(uploadedFileInfoList)
                .build();

        return Optional.of(dto);
    }

    private boolean isDeleted(TaskEntity entity) {
        return entity.getIsDeleted() != null && entity.getIsDeleted() == 1;
    }

    private TaskDetailDTO.TaskBaseInfo buildTaskBaseInfo(TaskEntity taskEntity) {
        List<Integer> formatList = parseFormatList(taskEntity.getFormat());
        int estRemainingTime = computeEstRemainingTime(taskEntity.getStatus(), taskEntity.getCompletePercent());

        return TaskDetailDTO.TaskBaseInfo.builder()
                .taskTitle(taskEntity.getTaskTitle())
                .taskDesc(taskEntity.getTaskDesc() != null ? taskEntity.getTaskDesc() : "")
                .taskStatus(taskEntity.getStatus())
                .startTime(toEpochSecond(taskEntity.getStartTime()))
                .dueTime(toEpochSecond(taskEntity.getDueDate()))
                .finishTime(toEpochSecond(taskEntity.getFinishTime()))
                .costTime(taskEntity.getCostTime() != null ? taskEntity.getCostTime() : 0)
                .subject(taskEntity.getSubject())
                .academicLevel(taskEntity.getAcademicLevel())
                .priorityLevel(taskEntity.getPriorityLevel() != null ? taskEntity.getPriorityLevel() : 0)
                .citationStyle(taskEntity.getCitationStyle() != null ? taskEntity.getCitationStyle() : 0)
                .pageLength(taskEntity.getPageLength() != null ? taskEntity.getPageLength() : 0)
                .formatList(formatList)
                .specialInstructions(taskEntity.getSpecialInstructions() != null ? taskEntity.getSpecialInstructions() : "")
                .completePercent(taskEntity.getCompletePercent() != null ? taskEntity.getCompletePercent().doubleValue() : 0.0)
                .taskCompletedSize(taskEntity.getTaskCompletedSize() != null ? taskEntity.getTaskCompletedSize() : 0)
                .activeAgentSize(taskEntity.getActiveAgentSize() != null ? taskEntity.getActiveAgentSize() : 0)
                .estRemainingTime(estRemainingTime)
                .queueAheadCount(0)
                .requirementJson(taskEntity.getRequirementJson())
                .build();
    }

    private List<Integer> parseFormatList(String format) {
        if (format == null || format.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Integer> list = gson.fromJson(format, formatListType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private long toEpochSecond(java.time.LocalDateTime dateTime) {
        return dateTime != null ? dateTime.atZone(ZoneId.systemDefault()).toEpochSecond() : 0L;
    }

    private Long toEpochSecondOrNull(java.time.LocalDateTime dateTime) {
        return dateTime != null ? dateTime.atZone(ZoneId.systemDefault()).toEpochSecond() : null;
    }

    private int computeEstRemainingTime(Integer statusCode, java.math.BigDecimal completePercent) {
        if (statusCode == null) {
            statusCode = TaskStatus.DRAFT.getCode();
        }
        if (statusCode.equals(TaskStatus.COMPLETED.getCode())
                || statusCode.equals(TaskStatus.FAILED.getCode())
                || statusCode.equals(TaskStatus.CANCELLED.getCode())) {
            return 0;
        }
        double percent = completePercent != null ? completePercent.doubleValue() : 0.0;
        percent = Math.max(0.0, Math.min(100.0, percent));
        int remaining = (int) Math.round(TOTAL_ESTIMATED_SECONDS * (1.0 - percent / 100.0));
        return Math.max(0, remaining);
    }

    private List<SubTaskEntity> loadSubTasks(Long taskId) {
        return subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTaskEntity>()
                        .eq(SubTaskEntity::getTaskId, taskId)
                        .orderByAsc(SubTaskEntity::getOrderIndex)
        );
    }

    private List<TaskAgentEntity> loadAgents(Long taskId) {
        return taskAgentMapper.selectList(
                new LambdaQueryWrapper<TaskAgentEntity>()
                        .eq(TaskAgentEntity::getTaskId, taskId)
                        .isNotNull(TaskAgentEntity::getSubtaskId)
                        .ne(TaskAgentEntity::getSubtaskId, "")
                        .orderByDesc(TaskAgentEntity::getUpdatedAt)
        );
    }

    private Map<String, TaskAgentEntity> buildSubtaskToAgentMap(List<TaskAgentEntity> agents) {
        Map<String, TaskAgentEntity> map = new HashMap<>();
        for (TaskAgentEntity agent : agents) {
            String subtaskId = agent.getSubtaskId();
            if (subtaskId != null && !subtaskId.trim().isEmpty()) {
                map.put(subtaskId.trim(), agent);
            }
        }
        return map;
    }

    private Map<String, TaskAgentEntity> buildAgentNameToAgentMap(List<TaskAgentEntity> agents) {
        Map<String, TaskAgentEntity> map = new HashMap<>();
        for (TaskAgentEntity agent : agents) {
            String agentName = agent.getAgentName();
            String subtaskId = agent.getSubtaskId();
            if (agentName != null && !agentName.trim().isEmpty()) {
                TaskAgentEntity existing = map.get(agentName);
                if (existing == null || ((existing.getSubtaskId() == null || existing.getSubtaskId().isEmpty())
                        && subtaskId != null && !subtaskId.isEmpty())) {
                    map.put(agentName, agent);
                }
            }
        }
        return map;
    }

    private List<TaskDetailDTO.SubTaskInfo> buildSubTaskInfoList(
            List<SubTaskEntity> subTaskEntities,
            Map<String, TaskAgentEntity> subtaskCodeToAgent,
            Map<String, TaskAgentEntity> agentNameToAgent) {

        List<TaskDetailDTO.SubTaskInfo> result = new ArrayList<>();
        for (SubTaskEntity st : subTaskEntities) {
            String subtaskCode = st.getSubtaskCode();
            String agentName = st.getAgentName();
            TaskAgentEntity agent = subtaskCodeToAgent.get(subtaskCode != null ? subtaskCode.trim() : null);
            if (agent == null && agentName != null && !agentName.trim().isEmpty()) {
                agent = agentNameToAgent.get(agentName.trim());
            }

            String finalAgentName = agentName;
            if (agent != null) {
                String tableName = agent.getAgentName();
                if (tableName != null && !tableName.trim().isEmpty()) {
                    finalAgentName = tableName;
                }
            }

            TaskDetailDTO.SubTaskInfo.SubTaskInfoBuilder builder = TaskDetailDTO.SubTaskInfo.builder()
                    .title(st.getTitle())
                    .desc(st.getDescription() != null ? st.getDescription() : "")
                    .processDesc(st.getProcessDesc() != null ? st.getProcessDesc() : "")
                    .agentName(finalAgentName != null ? finalAgentName : "")
                    .subtaskCode(subtaskCode);

            if (agent != null) {
                builder.agentStatus(agent.getAgentStatus())
                        .agentCompletePercent(agent.getCompletePercent() != null ? agent.getCompletePercent().doubleValue() : 0.0)
                        .agentDesc(agent.getAgentDesc() != null ? agent.getAgentDesc() : "")
                        .agentStartTime(toEpochSecond(agent.getAgentStartTime()))
                        .agentFinishTime(toEpochSecondOrNull(agent.getAgentFinishTime()))
                        .agentPriority(agent.getAgentPriority() != null ? agent.getAgentPriority() : 1)
                        .agentOutput(agent.getAgentOutput() != null ? agent.getAgentOutput() : "");
            } else {
                builder.agentStatus(0)
                        .agentCompletePercent(0.0)
                        .agentDesc("")
                        .agentStartTime(0L)
                        .agentFinishTime(null)
                        .agentPriority(1)
                        .agentOutput("");
            }
            result.add(builder.build());
        }
        return result;
    }

    private List<TaskDetailDTO.ActivityInfo> loadAndBuildActivities(Long taskId) {
        List<TaskActivityEntity> entities = taskActivityMapper.selectList(
                new LambdaQueryWrapper<TaskActivityEntity>()
                        .eq(TaskActivityEntity::getTaskId, taskId)
                        .orderByDesc(TaskActivityEntity::getActivityTime)
                        .last("LIMIT " + ACTIVITY_LIMIT)
        );
        return entities.stream()
                .map(act -> TaskDetailDTO.ActivityInfo.builder()
                        .activityTime(toEpochSecond(act.getActivityTime()))
                        .agentName(act.getAgentName())
                        .activityDesc(act.getActivityDesc())
                        .build())
                .collect(Collectors.toList());
    }

    private List<TaskDetailDTO.AgentInfo> buildAgentInfoList(
            List<TaskAgentEntity> agentEntities,
            List<SubTaskEntity> subTaskEntities) {

        if (agentEntities.isEmpty() && !subTaskEntities.isEmpty()) {
            Long taskId = subTaskEntities.get(0).getTaskId();
            List<TaskActivityEntity> activities = taskActivityMapper.selectList(
                    new LambdaQueryWrapper<TaskActivityEntity>().eq(TaskActivityEntity::getTaskId, taskId));
            return extractAgentsFromSubtasksAndActivities(subTaskEntities, activities);
        }

        Map<String, SubTaskEntity> subtaskCodeMap = subTaskEntities.stream()
                .filter(st -> st.getSubtaskCode() != null && !st.getSubtaskCode().isEmpty())
                .collect(Collectors.toMap(SubTaskEntity::getSubtaskCode, st -> st, (a, b) -> a));

        return agentEntities.stream()
                .map(agent -> {
                    String subtaskId = agent.getSubtaskId();
                    String subtaskTitle = subtaskId != null && subtaskCodeMap.containsKey(subtaskId)
                            ? subtaskCodeMap.get(subtaskId).getTitle()
                            : "";

                    return TaskDetailDTO.AgentInfo.builder()
                            .agentName(agent.getAgentName())
                            .subtaskId(subtaskId)
                            .subtaskTitle(subtaskTitle)
                            .agentStatus(agent.getAgentStatus())
                            .completePercent(agent.getCompletePercent() != null ? agent.getCompletePercent().doubleValue() : 0.0)
                            .agentDesc(agent.getAgentDesc() != null ? agent.getAgentDesc() : "")
                            .agentStartTime(toEpochSecond(agent.getAgentStartTime()))
                            .agentFinishTime(toEpochSecondOrNull(agent.getAgentFinishTime()))
                            .agentPriority(agent.getAgentPriority() != null ? agent.getAgentPriority() : 1)
                            .agentOutput(agent.getAgentOutput() != null ? agent.getAgentOutput() : "")
                            .build();
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("SameParameterValue")
    private List<TaskDetailDTO.AgentInfo> extractAgentsFromSubtasksAndActivities(
            List<SubTaskEntity> subtasks,
            List<TaskActivityEntity> activities) {

        if (subtasks == null) subtasks = new ArrayList<>();
        if (activities == null) activities = new ArrayList<>();

        Set<String> agentNames = new HashSet<>();
        subtasks.stream()
                .filter(st -> st.getAgentName() != null && !st.getAgentName().trim().isEmpty())
                .map(st -> st.getAgentName().trim())
                .forEach(agentNames::add);
        activities.stream()
                .filter(act -> act.getAgentName() != null && !act.getAgentName().trim().isEmpty())
                .map(act -> act.getAgentName().trim())
                .forEach(agentNames::add);

        if (agentNames.isEmpty()) return new ArrayList<>();

        List<TaskDetailDTO.AgentInfo> result = new ArrayList<>();
        for (String agentName : agentNames.stream().sorted().collect(Collectors.toList())) {
            List<SubTaskEntity> agentSubtasks = subtasks.stream()
                    .filter(st -> agentName.equals(st.getAgentName()))
                    .collect(Collectors.toList());
            double completePercent = 0.0;
            if (!agentSubtasks.isEmpty()) {
                long completed = agentSubtasks.stream()
                        .filter(st -> st.getStatus() != null && st.getStatus() == SUBTASK_STATUS_COMPLETED)
                        .count();
                completePercent = (completed * 100.0) / agentSubtasks.size();
            }
            long agentStartTime = 0L;
            Optional<TaskActivityEntity> earliest = activities.stream()
                    .filter(act -> agentName.equals(act.getAgentName()))
                    .min(Comparator.comparing(TaskActivityEntity::getActivityTime));
            if (earliest.isPresent() && earliest.get().getActivityTime() != null) {
                agentStartTime = earliest.get().getActivityTime().atZone(ZoneId.systemDefault()).toEpochSecond();
            }
            result.add(TaskDetailDTO.AgentInfo.builder()
                    .agentName(agentName)
                    .agentStatus(2)
                    .completePercent(completePercent)
                    .agentDesc("AI Agent: " + agentName)
                    .agentStartTime(agentStartTime)
                    .agentFinishTime(null)
                    .agentPriority(1)
                    .agentOutput("")
                    .build());
        }
        return result;
    }

    private List<TaskDetailDTO.OutputInfo> loadAndBuildOutputs(Long taskId) {
        List<TaskOutputEntity> outputs = taskOutputMapper.selectList(
                new LambdaQueryWrapper<TaskOutputEntity>().eq(TaskOutputEntity::getTaskId, taskId)
        );
        return outputs.stream()
                .map(out -> TaskDetailDTO.OutputInfo.builder()
                        .title(out.getTitle())
                        .desc(out.getDescription() != null ? out.getDescription() : "")
                        .url("/v1/task/output/download/" + out.getId())
                        .sizeDesc(out.getSizeDesc() != null ? out.getSizeDesc() : "")
                        .pageSize(out.getPageSize() != null ? out.getPageSize() : 0)
                        .format(out.getFormat() != null && out.getFormat() <= 4 ? out.getFormat() : 4)
                        .outputType(out.getOutputType() != null ? out.getOutputType() : 0)
                        .build())
                .collect(Collectors.toList());
    }

    private TaskDetailDTO.OutputInfo extractMainOutput(Long taskId) {
        List<TaskOutputEntity> outputs = taskOutputMapper.selectList(
                new LambdaQueryWrapper<TaskOutputEntity>().eq(TaskOutputEntity::getTaskId, taskId)
        );
        TaskOutputEntity main = outputs.stream()
                .filter(o -> o.getOutputType() != null && o.getOutputType() == 1)
                .findFirst()
                .orElse(outputs.isEmpty() ? null : outputs.get(0));
        if (main == null) return null;
        return TaskDetailDTO.OutputInfo.builder()
                .title(main.getTitle())
                .desc(main.getDescription() != null ? main.getDescription() : "")
                .url("/v1/task/output/download/" + main.getId())
                .sizeDesc(main.getSizeDesc() != null ? main.getSizeDesc() : "")
                .pageSize(main.getPageSize() != null ? main.getPageSize() : 0)
                .format(main.getFormat() != null && main.getFormat() <= 4 ? main.getFormat() : 4)
                .outputType(main.getOutputType() != null ? main.getOutputType() : 0)
                .build();
    }

    private List<TaskDetailDTO.UploadedFileInfo> loadUploadedFiles(Long taskId) {
        List<TaskFileEntity> taskFiles = taskFileMapper.selectList(
                new LambdaQueryWrapper<TaskFileEntity>()
                        .eq(TaskFileEntity::getTaskId, taskId)
                        .orderByAsc(TaskFileEntity::getFileOrder)
        );
        if (taskFiles.isEmpty()) return new ArrayList<>();

        Set<Long> fileIds = taskFiles.stream()
                .map(TaskFileEntity::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (fileIds.isEmpty()) return new ArrayList<>();

        List<FileEntity> files = fileMapper.selectBatchIds(fileIds);
        Map<Long, FileEntity> fileById = files.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(FileEntity::getId, f -> f));

        List<TaskDetailDTO.UploadedFileInfo> result = new ArrayList<>();
        for (TaskFileEntity tf : taskFiles) {
            FileEntity fe = fileById.get(tf.getFileId());
            if (fe == null) continue;
            String fileType = fe.getFileExtension();
            if (fileType != null && fileType.startsWith(".")) {
                fileType = fileType.substring(1);
            }
            result.add(TaskDetailDTO.UploadedFileInfo.builder()
                    .objectId(fe.getObjectId())
                    .fileName(fe.getOriginalFilename())
                    .fileType(fileType)
                    .fileSize(fe.getFileSize())
                    .uploadTime(toEpochSecondOrNull(fe.getCreatedAt()))
                    .downloadUrl("/v1/file/download/" + fe.getObjectId())
                    .build());
        }
        return result;
    }
}
