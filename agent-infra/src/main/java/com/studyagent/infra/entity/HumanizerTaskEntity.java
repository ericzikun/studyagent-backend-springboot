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

    /** 任务来源: HUMANIZER_PAGE / EDITOR */
    private String source;

    /** 任务标题：由 Python ConversationTitleService 经 MQ 生成（best-effort，可为 null） */
    @TableField("task_name")
    private String taskName;

    /** DETECT / HUMANIZE */
    @TableField("task_type")
    private String taskType;

    @TableField("input_text")
    private String inputText;

    /** CHARGING / PENDING / PROCESSING / COMPLETED / FAILED / QUOTA_EXHAUSTED / CANCELLED */
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

    /** SHA-256 of first 200 chars of result_text, for relaxed detect matching */
    @TableField("result_hash")
    private String resultHash;

    @TableField("elapsed_seconds")
    private Double elapsedSeconds;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    /** Quota ledger ID for refund on failure */
    @TableField("quota_ledger_id")
    private Long quotaLedgerId;

    /** Total word count of input text */
    @TableField("total_words")
    private Integer totalWords;

    /** Words consumed (quota deducted) so far — for streaming per-chunk billing */
    @TableField("consumed_words")
    private Integer consumedWords;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
