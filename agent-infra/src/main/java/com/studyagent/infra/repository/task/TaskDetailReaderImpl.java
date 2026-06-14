package com.studyagent.infra.repository.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.studyagent.infra.entity.*;
import com.studyagent.infra.mapper.*;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.service.application.dto.TaskDetailDTO;
import com.studyagent.service.domain.task.TaskDetailReader;
import com.studyagent.service.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.lang.reflect.Type;
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
    private static final String PHASE_COMPOSE = "COMPOSE";

    /** 模拟进度窗口：任务开始后前 N 秒内若 Python 未推送真实进度，则按时间线性展示 0~10% */
    private static final int SIMULATED_PROGRESS_WINDOW_SECONDS = 120;
    private static final double SIMULATED_PROGRESS_MAX_PERCENT = 10.0;

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

        // COMPLETED TASKS: 仅统计 PLANNING 阶段已完成的子任务数（不含 COMPOSE 阶段）
        int planningCompletedCount = (int) subTaskEntities.stream()
                .filter(st -> st.getStatus() != null && st.getStatus() == SUBTASK_STATUS_COMPLETED)
                .filter(st -> isPlanningPhase(st))
                .count();
        taskBaseInfo.setTaskCompletedSize(planningCompletedCount);
        // activeAgentSize (SECTIONS DRAFTED) 已在 buildTaskBaseInfo 中通过 resolveActiveAgentSize 设置为 compose_total_rounds，不再覆盖

        Map<String, TaskAgentEntity> subtaskCodeToAgent = buildSubtaskToAgentMap(agentEntities);
        Map<String, TaskAgentEntity> agentNameToAgent = buildAgentNameToAgentMap(agentEntities);

        List<TaskDetailDTO.SubTaskInfo> subTaskInfoList = buildSubTaskInfoList(subTaskEntities, subtaskCodeToAgent, agentNameToAgent);
        List<TaskDetailDTO.ActivityInfo> activityInfoList = loadAndBuildActivities(taskId);
        List<TaskDetailDTO.AgentInfo> agentInfoList = buildAgentInfoList(agentEntities, subTaskEntities);
        List<TaskDetailDTO.OutputInfo> outputDetailInfoList = loadAndBuildOutputs(taskId);
        TaskDetailDTO.OutputInfo outputSummaryInfo = extractMainOutput(outputDetailInfoList);
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

    /**
     * 判断子任务是否属于 PLANNING 阶段（排除 COMPOSE 阶段）
     * - subtask_code 以 "compose." 开头则为 COMPOSE 阶段
     * - phase=COMPOSE 则为 COMPOSE 阶段
     * - 其余视为 PLANNING
     */
    private boolean isPlanningPhase(SubTaskEntity st) {
        String code = st.getSubtaskCode();
        if (code != null && code.startsWith("compose.")) {
            return false;
        }
        String phase = st.getPhase();
        if (phase != null && PHASE_COMPOSE.equalsIgnoreCase(phase)) {
            return false;
        }
        return true;
    }

    private TaskDetailDTO.TaskBaseInfo buildTaskBaseInfo(TaskEntity taskEntity) {
        List<Integer> formatList = parseFormatList(taskEntity.getFormat());
        double effectivePercent = resolveCompletePercent(taskEntity);
        int estRemainingTime = computeEstRemainingTime(taskEntity.getStatus(), java.math.BigDecimal.valueOf(effectivePercent));

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
                .completePercent(effectivePercent)
                .taskCompletedSize(taskEntity.getTaskCompletedSize() != null ? taskEntity.getTaskCompletedSize() : 0)
                .activeAgentSize(resolveActiveAgentSize(taskEntity))
                .estRemainingTime(estRemainingTime)
                .queueAheadCount(0)
                .requirementJson(taskEntity.getRequirementJson())
                .build();
    }

    /**
     * 解析任务完成百分比：任务执行中，在 Python 上报仍偏低时，用「自 start_time 起前 2 分钟线性涨到 10%」的模拟值打底；
     * 超过 2 分钟后模拟值封顶 10% 不再归零（避免与 Python 规划阶段 completePercent=0 叠加造成进度条跌回 0）；
     * 展示值始终为 max(真实, 模拟打底)。
     */
    private double resolveCompletePercent(TaskEntity taskEntity) {
        double realPercent = taskEntity.getCompletePercent() != null
                ? taskEntity.getCompletePercent().doubleValue() : 0.0;

        if (taskEntity.getStatus() == null || !taskEntity.getStatus().equals(TaskStatus.IN_PROGRESS.getCode())) {
            return realPercent;
        }
        if (taskEntity.getStartTime() == null) {
            return realPercent;
        }

        long nowEpoch = System.currentTimeMillis() / 1000;
        long startEpoch = taskEntity.getStartTime().atZone(DateTimeFormats.APP_ZONE).toEpochSecond();
        long elapsedSeconds = Math.max(0, nowEpoch - startEpoch);

        long effectiveElapsed = Math.min(elapsedSeconds, SIMULATED_PROGRESS_WINDOW_SECONDS);
        double simulatedPercent = (effectiveElapsed * SIMULATED_PROGRESS_MAX_PERCENT) / SIMULATED_PROGRESS_WINDOW_SECONDS;
        simulatedPercent = Math.min(simulatedPercent, SIMULATED_PROGRESS_MAX_PERCENT);

        return Math.max(realPercent, simulatedPercent);
    }

    /**
     * 解析 SECTIONS DRAFTED (activeAgentSize) 的值：
     * - 若任务已进入 COMPOSE 阶段（compose_total_rounds > 0），则使用 compose_total_rounds 表示计划章节数
     * - 否则使用原 active_agent_size（未进入 compose 或拿不到则为 0）
     */
    private int resolveActiveAgentSize(TaskEntity taskEntity) {
        if (taskEntity.getComposeTotalRounds() != null && taskEntity.getComposeTotalRounds() > 0) {
            return taskEntity.getComposeTotalRounds();
        }
        return taskEntity.getActiveAgentSize() != null ? taskEntity.getActiveAgentSize() : 0;
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
        return DateTimeFormats.toEpochSecond(dateTime);
    }

    private Long toEpochSecondOrNull(java.time.LocalDateTime dateTime) {
        return DateTimeFormats.toEpochSecondOrNull(dateTime);
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
                agentStartTime = DateTimeFormats.toEpochSecond(earliest.get().getActivityTime());
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

    private TaskDetailDTO.OutputInfo extractMainOutput(List<TaskDetailDTO.OutputInfo> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        return outputs.stream()
                .filter(o -> o.getOutputType() != null && o.getOutputType() == 1)
                .findFirst()
                .orElse(outputs.get(0));
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
