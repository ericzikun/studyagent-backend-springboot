package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_workforce_task_outputs 表实体。
 */
@Data
@Accessors(chain = true)
@TableName("verla_workforce_task_outputs")
public class VerlaWorkforceTaskOutputEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long turnId;
    private Long sessionId;

    private String nodeId;

    private String resultText;
    private String detailItemsJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
