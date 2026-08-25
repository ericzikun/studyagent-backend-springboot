package com.studyagent.api.dto.response;

/**
 * 统一的邮箱留资受理结果，不暴露新增、重复或蜜罐命中状态。
 */
public record PublicEmailLeadAcceptedResponse(boolean accepted) {
}
