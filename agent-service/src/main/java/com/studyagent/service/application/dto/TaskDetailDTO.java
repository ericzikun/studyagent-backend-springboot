package com.studyagent.service.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务详情数据传输对象（应用层）
 * 与 API 层 TaskDetailResponse 结构一致，便于转换
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailDTO {

    private TaskBaseInfo taskBaseInfo;
    private List<AgentInfo> agentInfoList;
    private List<SubTaskInfo> subTaskInfoList;
    private List<ActivityInfo> activityInfoList;
    private OutputInfo outputSummaryInfo;
    private List<OutputInfo> outputDetailInfoList;
    private List<UploadedFileInfo> uploadedFileInfoList;

    /**
     * 追问 Q&amp;A 与追问内附件 objectId 引用（由 requirementJson 解析，便于详情直接展示）
     */
    private List<ClarifyingQuestionInfo> clarifyingQuestionList;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskBaseInfo {
        private String taskTitle;
        private String taskDesc;
        private Integer taskStatus;
        private Long startTime;
        private Long dueTime;
        private Long finishTime;
        private Integer costTime;
        private Integer subject;
        private Integer academicLevel;
        private Integer priorityLevel;
        private Integer citationStyle;
        private Integer pageLength;
        private List<Integer> formatList;
        private String specialInstructions;
        private Double completePercent;
        private Integer taskCompletedSize;
        private Integer activeAgentSize;
        private Integer estRemainingTime;
        private Integer queueAheadCount;
        private String requirementJson;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentInfo {
        private String agentName;
        private String subtaskId;
        private String subtaskTitle;
        private Integer agentStatus;
        private Double completePercent;
        private String agentDesc;
        private Long agentStartTime;
        private Long agentFinishTime;
        private Integer agentPriority;
        private String agentOutput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTaskInfo {
        private String title;
        private String desc;
        private String processDesc;
        private String agentName;
        private String subtaskCode;
        private Integer agentStatus;
        private Double agentCompletePercent;
        private String agentDesc;
        private Long agentStartTime;
        private Long agentFinishTime;
        private Integer agentPriority;
        private String agentOutput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityInfo {
        private Long activityTime;
        private String agentName;
        private String activityDesc;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputInfo {
        private String title;
        private String desc;
        private String url;
        private String sizeDesc;
        private Integer pageSize;
        private Integer format;
        private Integer outputType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadedFileInfo {
        private String objectId;
        private String fileName;
        private String fileType;
        private Long fileSize;
        private Long uploadTime;
        private String downloadUrl;
        /** TASK：主任务上传；CLARIFY：追问附件 */
        private String attachmentSource;
        /** 追问条目 id（与 clarifyingQuestions JSON 中 id 对齐） */
        private String clarifyQuestionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClarifyAttachmentInfo {
        private String objectId;
        private String filename;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClarifyingQuestionInfo {
        private String id;
        private String question;
        private String tag;
        private String answer;
        private Boolean skipped;
        private List<ClarifyAttachmentInfo> attachments;
    }
}
