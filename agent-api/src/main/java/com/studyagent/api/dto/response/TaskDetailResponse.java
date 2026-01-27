package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 任务详情响应 DTO
 * 使用驼峰命名（camelCase）风格，前后端统一
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailResponse {
    
    /**
     * 任务基础信息
     */
    private TaskBaseInfoResponse taskBaseInfo;
    
    /**
     * Agent信息列表
     */
    private List<AgentInfoResponse> agentInfoList;
    
    /**
     * 子任务信息列表
     */
    private List<SubTaskInfoResponse> subTaskInfoList;
    
    /**
     * 子任务信息映射（按Agent分组）
     */
    private Map<String, List<SubTaskInfoResponse>> subTaskInfoMap;
    
    /**
     * 活动信息列表
     */
    private List<ActivityInfoResponse> activityInfoList;
    
    /**
     * 活动信息映射（按时间戳）
     */
    private Map<String, ActivityInfoResponse> activityInfoMap;
    
    /**
     * 输出汇总信息
     */
    private OutputInfoResponse outputSummaryInfo;
    
    /**
     * 输出详细信息列表
     */
    private List<OutputInfoResponse> outputDetailInfoList;
    
    /**
     * 上传文件信息列表
     */
    private List<UploadedFileInfoResponse> uploadedFileInfoList;
    
    /**
     * 任务基础信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskBaseInfoResponse {
        private String taskTitle;
        
        private String taskDesc;
        
        private Integer taskStatus;
        
        private Long startTime; // Unix时间戳（秒）
        
        private Long dueTime; // Unix时间戳（秒）
        
        private Long finishTime; // Unix时间戳（秒）
        
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

        /**
         * 任务前方排队数量（为0表示已开始执行）
         */
        private Integer queueAheadCount;

        /**
         * 需求理解 JSON
         */
        private String requirementJson;
    }
    
    /**
     * Agent信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentInfoResponse {
        private String agentName;
        
        private Integer agentStatus;
        
        private Double completePercent;
        
        private String agentDesc;
        
        private Long agentStartTime; // Unix时间戳（秒）
        
        private Integer agentPriority;
        
        private String agentOutput;
    }
    
    /**
     * 子任务信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTaskInfoResponse {
        private String title;
        private String desc;
        
        private String processDesc;
        
        private String agentName;
    }
    
    /**
     * 活动信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityInfoResponse {
        private Long activityTime; // Unix时间戳（秒）
        
        private String agentName;
        
        private String activityDesc;
    }
    
    /**
     * 输出信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputInfoResponse {
        private String title;
        private String desc;
        private String url;
        
        private String sizeDesc;
        
        private Integer pageSize;
        
        private Integer format;
        
        /** 输出类型：0-日志文件，1-报告内容（终稿） */
        private Integer outputType;
    }
    
    /**
     * 上传文件信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadedFileInfoResponse {
        /** 文件唯一标识符（用于下载） */
        private String objectId;
        
        /** 文件名（包含扩展名，如 "requirements.pdf"） */
        private String fileName;
        
        /** 文件类型/扩展名（如 "pdf", "docx"） */
        private String fileType;
        
        /** 文件大小（字节） */
        private Long fileSize;
        
        /** 上传时间戳（Unix时间戳，秒） */
        private Long uploadTime;
        
        /** 文件下载链接 */
        private String downloadUrl;
    }
}

