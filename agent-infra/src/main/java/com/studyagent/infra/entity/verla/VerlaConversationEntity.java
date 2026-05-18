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
 * verla_conversations 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.3 / §11.1。
 */
@Data
@Accessors(chain = true)
@TableName("verla_conversations")
public class VerlaConversationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Clerk userId 或内部用户 ID */
    private String userId;

    private String title;

    /** active / archived / deleted */
    private String status;

    /** 沉淀的主意图（下一轮可跳 plan） */
    private String primaryIntent;

    /** 全局偏好/工具开关 */
    private String workspaceJson;

    private Integer turnCount;

    private Long lastTurnId;

    private LocalDateTime lastMessageAt;

    /** Redis 缓存 key 版本号（写时 +1） */
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
