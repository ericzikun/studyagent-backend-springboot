package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Humanizer/AI检测 异步任务实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("humanizer_tasks")
public class HumanizerTaskEntity extends BaseEntity {

    @TableField("clerk_user_id")
    private String clerkUserId;

    /** DETECT / HUMANIZE */
    @TableField("task_type")
    private String taskType;

    @TableField("input_text")
    private String inputText;

    /** PENDING / PROCESSING / COMPLETED / FAILED */
    private String status;

    /** Overall AI probability, DETECT only */
    private Double probability;

    /** AI Generated / Human Written, DETECT only */
    private String label;

    /** Per-sentence results JSON array, updated incrementally */
    @TableField("sentences_json")
    private String sentencesJson;

    /** Total sentence count, known after first chunk */
    @TableField("total_sentences")
    private Integer totalSentences;

    /** Sentences completed so far */
    @TableField("completed_sentences")
    private Integer completedSentences;

    /** Rewritten text, HUMANIZE only */
    @TableField("result_text")
    private String resultText;

    @TableField("elapsed_seconds")
    private Double elapsedSeconds;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
