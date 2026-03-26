package com.studyagent.service.application.dto;

/**
 * 保存草稿结果（支持新建路径下的短时间内容幂等）
 */
public record SaveDraftResult(long draftId, boolean deduplicated) {
}
