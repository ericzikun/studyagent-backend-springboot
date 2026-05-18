package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_clarify_responses 表实体（V2）。
 */
@Data
@Accessors(chain = true)
@TableName("verla_clarify_responses")
public class VerlaClarifyResponseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String responseUid;
    private String formId;
    private Long conversationId;
    private Long turnId;
    private String userId;
    private String answersJson;
    private LocalDateTime submittedAt;
}
