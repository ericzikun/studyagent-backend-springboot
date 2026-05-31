package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_workforce_tasks 表实体。
 */
@Data
@Accessors(chain = true)
@TableName("verla_workforce_tasks")
public class VerlaWorkforceTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long turnId;
    private Long sessionId;

    private String nodeId;
    private String camelTaskId;
    private String nodeKind;

    private String taskName;
    private String taskType;
    private String description;
    private String taskAgent;

    private String status;
    private String content;

    private String planStepsJson;
    private Integer planTaskCount;

    /** compose 节点：当前已完成的 compose 轮次 */
    private Integer composeCurrentRound;
    /** compose / plan 节点：compose 总轮次 */
    private Integer composeTotalRounds;

    private Integer sortOrder;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer processingTimeMs;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
