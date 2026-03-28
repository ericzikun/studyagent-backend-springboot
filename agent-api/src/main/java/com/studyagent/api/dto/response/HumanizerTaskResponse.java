package com.studyagent.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Humanizer 异步任务响应
 * 提交和查询共用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HumanizerTaskResponse {

    /** 任务ID */
    private Long id;

    /** 任务ID（兼容前端 taskId 字段） */
    public Long getTaskId() {
        return id;
    }

    /** DETECT / HUMANIZE */
    private String taskType;

    /** 用户输入的原始文本 */
    private String inputText;

    /** PENDING / PROCESSING / COMPLETED / FAILED */
    private String status;

    // ===== Detect 结果 =====
    /** 整体 AI 概率 */
    private Double probability;
    /** AI Generated / Human Written */
    private String label;
    /** 逐句结果 JSON 字符串（前端自行解析） */
    private String sentencesJson;
    /** 总句子数 */
    private Integer totalSentences;
    /** 已完成句子数 */
    private Integer completedSentences;

    // ===== Humanize 结果 =====
    /** 改写后文本 */
    private String resultText;

    // ===== 通用 =====
    /** 耗时 */
    private Double elapsedSeconds;
    /** 预计剩余时间（秒） */
    private Integer estimatedSeconds;
    /** 预计排队等待时间（秒），仅 PENDING 状态有值 */
    private Integer estimatedQueueSeconds;
    /** 排队位置（前面还有几个任务） */
    private Integer queuePosition;
    /** 错误信息 */
    private String errorMessage;

    /** 任务总 word 数 */
    private Integer totalWords;
    /** 已扣费 word 数（逐块扣费进度） */
    private Integer consumedWords;

    /** 进度百分比 0~100，未完成时最低为 1，完成时为 100 */
    private Integer progress;

    private String createdAt;
}
