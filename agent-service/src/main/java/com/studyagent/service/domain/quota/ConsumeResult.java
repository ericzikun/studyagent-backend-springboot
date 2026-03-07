package com.studyagent.service.domain.quota;

/**
 * 额度消费结果
 * 返回 ledger_id 用于任务失败时回滚
 */
public record ConsumeResult(long ledgerId) {}
