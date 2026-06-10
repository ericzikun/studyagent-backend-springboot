package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Conversation 领域对象
 * <p>
 * 与 verla_conversations 表字段一一对应，由 infra 模块负责 entity ↔ domain 转换。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversation {

    private Long id;
    private String userId;
    private String title;
    /** active / archived / deleted（与 SQL 字面量一致） */
    private String status;
    private String primaryIntent;
    /** none / awaiting_user_confirmation / committed */
    private String intentLifecycle;
    private String workspaceJson;
    private Integer turnCount;
    private Long lastTurnId;
    private LocalDateTime lastMessageAt;
    /** 改动时间：用户点击任务 / 编辑内容 / 发送消息时刷新（Recent Task 排序键） */
    private LocalDateTime lastActiveAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
