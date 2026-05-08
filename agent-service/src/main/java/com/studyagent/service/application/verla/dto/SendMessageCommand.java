package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * onUserMessage 入参（应用层 DTO，不暴露 controller 直接承接）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageCommand {

    private Long conversationId;

    /** 触发本次提交的用户：clerkUserId */
    private String userId;

    private String text;

    /** 前端幂等 ID（MVP 暂不强制去重，预留） */
    private String clientMessageId;

    /** 附件 JSON（透传） */
    private String attachmentsJson;

    /**
     * 是否允许在已有 primaryIntent 时跳 plan
     * <p>
     * MVP（Day 2）始终走 plan 路径，PR-12 才接通 direct capability dispatch。
     */
    @Builder.Default
    private boolean skipPlanIfPossible = true;

    /**
     * 若设为 {@code AI_DETECTION} / {@code AI_HUMANIZER}，则<strong>跳过意图识别 Plan</strong>，
     * 直接进入对应 capability（派发 {@code cmd.detection.run} / {@code cmd.humanizer.run}）。
     */
    private String forceIntent;
}
