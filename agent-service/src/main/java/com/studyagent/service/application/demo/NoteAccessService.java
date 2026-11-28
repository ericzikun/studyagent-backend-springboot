package com.studyagent.service.application.demo;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.quota.VerlaQuotaService;
import com.studyagent.service.domain.billing.BillingDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AI Notes 会员准入：有效付费会员（含 Pro Trial）或内部豁免账号才能发起笔记生成。
 * <p>已保存的笔记仍可查看；这里只拦 generate / 知识图重试这类会产生 AI 成本的入口。
 */
@Service
@RequiredArgsConstructor
public class NoteAccessService {

    private final BillingDomainService billingDomainService;
    private final VerlaQuotaService verlaQuotaService;

    public boolean canUseNotes(String clerkUserId) {
        if (verlaQuotaService.isCommercialExempt(clerkUserId)) {
            return true;
        }
        return billingDomainService.isPaidMember(clerkUserId);
    }

    public void assertCanUseNotes(String clerkUserId) {
        if (!canUseNotes(clerkUserId)) {
            throw new BusinessException(
                    ApiCode.NOTE_MEMBER_REQUIRED.getCode(),
                    ApiCode.NOTE_MEMBER_REQUIRED.getMessage());
        }
    }
}
