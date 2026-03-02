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

    /** DETECT / HUMANIZE */
    private String taskType;

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
    /** 错误信息 */
    private String errorMessage;

    private String createdAt;
}
