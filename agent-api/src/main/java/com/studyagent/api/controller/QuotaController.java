package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.quota.QuotaBalance;
import com.studyagent.service.domain.quota.QuotaDomainService;
import com.studyagent.service.domain.quota.QuotaLedgerItem;
import com.studyagent.service.domain.quota.QuotaLedgerPageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 额度查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/quota")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaDomainService quotaDomainService;

    /**
     * 查询用户额度余额
     * - 不传 feature_code 或为空：返回所有功能类型的额度列表
     * - 传 feature_code：返回指定功能类型的额度
     *
     * GET /v1/quota/balance
     * GET /v1/quota/balance?feature_code=task_create
     */
    @GetMapping("/balance")
    public Result<?> getBalance(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId,
            @RequestParam(value = "feature_code", required = false) String featureCode) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        if (featureCode != null && !featureCode.isEmpty()) {
            // 单功能点
            QuotaBalance balance = quotaDomainService.getUserQuota(clerkUserId, featureCode);
            Map<String, Object> freeQuota = new LinkedHashMap<>();
            freeQuota.put("balance", balance.freeBalance());
            freeQuota.put("period_total", balance.freePeriodTotal());
            freeQuota.put("period_end", balance.freePeriodEnd() != null ? balance.freePeriodEnd().toString() : null);

            Map<String, Object> paidQuota = new LinkedHashMap<>();
            paidQuota.put("balance", balance.paidBalance());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("feature_code", balance.featureCode());
            data.put("feature_name", balance.featureName());
            data.put("quota_unit", balance.quotaUnit());
            data.put("free_quota", freeQuota);
            data.put("paid_quota", paidQuota);
            data.put("total_available", balance.totalAvailable());

            return Result.success(data);
        }

        // 所有功能类型
        List<QuotaBalance> balances = quotaDomainService.getAllUserQuotas(clerkUserId);
        List<Map<String, Object>> items = balances.stream()
                .map(balance -> {
                    Map<String, Object> freeQuota = new LinkedHashMap<>();
                    freeQuota.put("balance", balance.freeBalance());
                    freeQuota.put("period_total", balance.freePeriodTotal());
                    freeQuota.put("period_end", balance.freePeriodEnd() != null ? balance.freePeriodEnd().toString() : null);

                    Map<String, Object> paidQuota = new LinkedHashMap<>();
                    paidQuota.put("balance", balance.paidBalance());

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("feature_code", balance.featureCode());
                    m.put("feature_name", balance.featureName());
                    m.put("quota_unit", balance.quotaUnit());
                    m.put("free_quota", freeQuota);
                    m.put("paid_quota", paidQuota);
                    m.put("total_available", balance.totalAvailable());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return Result.success(data);
    }

    /**
     * 分页查询用户额度流水
     * GET /v1/quota/ledger?page=1&page_size=20&feature_code=task_create
     */
    @GetMapping("/ledger")
    public Result<Map<String, Object>> getLedger(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(value = "feature_code", required = false) String featureCode) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));

        QuotaLedgerPageResult result = quotaDomainService.getLedgerPage(clerkUserId, featureCode, safePage, safePageSize);

        List<Map<String, Object>> items = result.items().stream()
                .map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", item.id());
                    m.put("ledgerNo", item.ledgerNo());
                    m.put("ledgerType", item.ledgerType());
                    m.put("amount", item.amount());
                    m.put("sourceType", item.sourceType());
                    m.put("sourceId", item.sourceId());
                    m.put("displayText", item.displayText());
                    m.put("freeBalanceAfter", item.freeBalanceAfter());
                    m.put("paidBalanceAfter", item.paidBalanceAfter());
                    m.put("createdAt", item.createdAt() != null ? item.createdAt().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", result.total());
        data.put("page", safePage);
        data.put("pageSize", safePageSize);

        return Result.success(data);
    }
}
