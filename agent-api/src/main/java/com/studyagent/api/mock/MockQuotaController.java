package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/quota")
@RequiredArgsConstructor
public class MockQuotaController {

    private final MockAuthSupport mockAuthSupport;

    @GetMapping("/balance")
    public Result<Object> getBalance(
        @RequestParam(value = "feature_code", required = false) String featureCode,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        List<Map<String, Object>> items = List.of(
            balanceItem("task_create", "作业任务", "count", 2, 3, 15),
            balanceItem("ai_detection", "AI Detection", "words", 5000, 10000, 45000),
            balanceItem("humanizer", "Humanizer", "words", 5000, 10000, 25000)
        );

        if (featureCode == null || featureCode.isBlank()) {
            Map<String, Object> data = new HashMap<>();
            data.put("items", items);
            return Result.success(data);
        }

        return items.stream()
            .filter(item -> featureCode.equals(item.get("feature_code")))
            .findFirst()
            .<Result<Object>>map(Result::success)
            .orElseGet(() -> Result.success(null));
    }

    @GetMapping("/ledger")
    public Result<Map<String, Object>> getLedger(
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize,
        @RequestParam(value = "feature_code", required = false) String featureCode,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);

        List<Map<String, Object>> all = new ArrayList<>();
        all.add(ledgerItem(1001, "recharge", 10, "充值 10 Assignments，到账 10 次（$23.99）", "task_create", "2026-02-28T14:30:22"));
        all.add(ledgerItem(1000, "consume", -1, "作业任务 消耗 1 次", "task_create", "2026-02-28T14:20:11"));
        all.add(ledgerItem(999, "refund", 1, "任务失败，退回 1 次", "task_create", "2026-02-28T14:10:00"));
        all.add(ledgerItem(998, "consume", -1200, "AI Detection 消耗 1200 words", "ai_detection", "2026-02-27T09:10:00"));
        all.add(ledgerItem(997, "consume", -800, "Humanizer 消耗 800 words", "humanizer", "2026-02-26T21:05:00"));

        List<Map<String, Object>> filtered = all;
        if (featureCode != null && !featureCode.isBlank()) {
            filtered = all.stream()
                .filter(item -> featureCode.equals(item.get("feature_code")))
                .toList();
        }

        int total = filtered.size();
        int from = Math.min((safePage - 1) * safePageSize, total);
        int to = Math.min(from + safePageSize, total);

        Map<String, Object> data = new HashMap<>();
        data.put("items", filtered.subList(from, to));
        data.put("total", total);
        data.put("page", safePage);
        data.put("pageSize", safePageSize);

        return Result.success(data);
    }

    private Map<String, Object> balanceItem(
        String featureCode,
        String featureName,
        String unit,
        int freeBalance,
        int freeTotal,
        int paidBalance
    ) {
        Map<String, Object> item = new HashMap<>();
        item.put("feature_code", featureCode);
        item.put("feature_name", featureName);
        item.put("quota_unit", unit);

        Map<String, Object> free = new HashMap<>();
        free.put("balance", freeBalance);
        free.put("period_total", freeTotal);
        free.put("period_end", LocalDateTime.now().plusDays(30).withNano(0).toString());
        item.put("free_quota", free);

        Map<String, Object> paid = new HashMap<>();
        paid.put("balance", paidBalance);
        item.put("paid_quota", paid);

        item.put("total_available", freeBalance + paidBalance);
        return item;
    }

    private Map<String, Object> ledgerItem(
        long id,
        String ledgerType,
        int amount,
        String displayText,
        String featureCode,
        String createdAt
    ) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("ledgerType", ledgerType);
        item.put("amount", amount);
        item.put("displayText", displayText);
        item.put("feature_code", featureCode);
        item.put("createdAt", createdAt);
        return item;
    }
}
