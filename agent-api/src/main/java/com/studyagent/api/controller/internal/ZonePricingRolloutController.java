package com.studyagent.api.controller.internal;

import com.studyagent.api.common.Result;
import com.studyagent.infra.service.billing.SubscriptionPriceRolloutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存量合同下周期换价入口（写操作，全部幂等可重跑）。执行语义在
 * {@link SubscriptionPriceRolloutService}。请求头 {@code X-Internal-Token}
 * （{@code billing.zone-pricing.internal-token}，默认空=拒绝）。
 *
 * <p>操作顺序：dry-run（落 preview/conflict/skipped 分类并回填合同三列）→ 人工核对
 * → apply（写 SubscriptionSchedule，幂等键 rollout:{batch}:{sub}:{price}:{effectiveAt}）
 * → verify（三态推进 scheduled/applied/failed）；cancel 用写入前快照原样恢复。
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/billing/zone-pricing/rollout")
@RequiredArgsConstructor
public class ZonePricingRolloutController {

    private final SubscriptionPriceRolloutService rolloutService;

    @Value("${billing.zone-pricing.internal-token:}")
    private String internalToken;

    @PostMapping("/dry-run")
    public Result<SubscriptionPriceRolloutService.DryRunSummary> dryRun(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String zoneCode,
            @RequestParam String targetVersion) {
        if (!authorized(token)) {
            return Result.error(403, "invalid or missing X-Internal-Token");
        }
        SubscriptionPriceRolloutService.DryRunSummary summary =
                rolloutService.dryRun(zoneCode, targetVersion);
        log.info("zone-pricing rollout dry-run: {}", summary);
        return Result.success(summary);
    }

    @PostMapping("/apply")
    public Result<SubscriptionPriceRolloutService.ApplySummary> apply(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String batchId) {
        if (!authorized(token)) {
            return Result.error(403, "invalid or missing X-Internal-Token");
        }
        SubscriptionPriceRolloutService.ApplySummary summary = rolloutService.apply(batchId);
        log.info("zone-pricing rollout apply: {}", summary);
        return Result.success(summary);
    }

    @PostMapping("/verify")
    public Result<SubscriptionPriceRolloutService.VerifySummary> verify(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String batchId) {
        if (!authorized(token)) {
            return Result.error(403, "invalid or missing X-Internal-Token");
        }
        SubscriptionPriceRolloutService.VerifySummary summary = rolloutService.verify(batchId);
        log.info("zone-pricing rollout verify: {}", summary);
        return Result.success(summary);
    }

    @PostMapping("/cancel")
    public Result<SubscriptionPriceRolloutService.CancelSummary> cancel(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam String batchId) {
        if (!authorized(token)) {
            return Result.error(403, "invalid or missing X-Internal-Token");
        }
        SubscriptionPriceRolloutService.CancelSummary summary = rolloutService.cancel(batchId);
        log.info("zone-pricing rollout cancel: {}", summary);
        return Result.success(summary);
    }

    private boolean authorized(String token) {
        return internalToken != null && !internalToken.isBlank()
                && internalToken.equals(token);
    }
}
