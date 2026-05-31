package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_messages 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4 / §22.2。
 */
@Data
@Accessors(chain = true)
@TableName("verla_messages")
public class VerlaMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long turnId;

    /** user / assistant / system / agent_workforce */
    private String role;

    /** assistant 消息的来源 session（plan/agent） */
    private Long sourceSessionId;

    private String textContent;

    /** 卡片 / block 数组（见 §22.2） */
    private String blocksJson;

    private String attachmentsJson;

    private String metaJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
