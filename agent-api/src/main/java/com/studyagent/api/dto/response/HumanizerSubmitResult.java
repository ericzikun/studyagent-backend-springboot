package com.studyagent.api.dto.response;

/**
 * Humanizer/AI Detection 任务提交结果（含额度扣减标识）
 */
public record HumanizerSubmitResult(HumanizerTaskResponse response, boolean quotaConsumed) {}
