package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * onUserMessage 出参
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageResult {

    private Long turnId;

    private Long userMessageId;

    /** 派发的 plan session id（若 skipPlan 命中则为 null） */
    private Long planSessionId;

    /** 派发的 agent session id（PR-12 才会非空） */
    private Long agentSessionId;

    /** 跳 plan 的原因，例如 "primary_intent_cached" */
    private String skipPlanReason;
}
