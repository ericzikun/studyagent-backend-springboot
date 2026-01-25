package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务评价表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_ratings")
public class TaskRatingEntity extends BaseEntity {
    private Long taskId;
    private java.math.BigDecimal score;
    private String content;
}

