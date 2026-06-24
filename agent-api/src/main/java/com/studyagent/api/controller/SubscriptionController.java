package com.studyagent.api.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import com.studyagent.service.domain.billing.BillingDomainException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.SubscriptionResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/subscription")
@RequiredArgsConstructor
public class SubscriptionController {
    private final BillingDomainService billingDomainService;

    @GetMapping("/current")
    public Result<SubscriptionResult> current(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        return Result.success(billingDomainService.getCurrentSubscription(clerkUserId));
    }

    @PostMapping("/cancel")
    public Result<SubscriptionResult> cancel(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        return execute(clerkUserId, () -> billingDomainService.cancelAtPeriodEnd(clerkUserId));
    }

    @PostMapping("/resume")
    public Result<SubscriptionResult> resume(
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        return execute(clerkUserId, () -> billingDomainService.resumeSubscription(clerkUserId));
    }

    @PostMapping("/change")
    public Result<SubscriptionResult> change(
            @RequestBody ChangePlanRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        return execute(clerkUserId, () -> billingDomainService.changeSubscription(clerkUserId, request.getResolvedPlanCode()));
    }

    @PostMapping("/upgrade")
    @Deprecated(forRemoval = false)
    public Result<SubscriptionResult> upgrade(
            @RequestBody ChangePlanRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        return Result.error(
                ApiCode.BAD_REQUEST,
                "This endpoint has been deprecated. Use /v1/payment/subscription-checkout instead.");
    }

    @PostMapping("/downgrade")
    public Result<SubscriptionResult> downgrade(
            @RequestBody ChangePlanRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        return execute(clerkUserId, () -> billingDomainService.downgradeSubscription(clerkUserId, request.getResolvedPlanCode()));
    }

    private Result<SubscriptionResult> execute(String clerkUserId, SubscriptionAction action) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        try {
            return Result.success(action.execute());
        } catch (BillingDomainException e) {
            return switch (e.getCode()) {
                case "SUBSCRIPTION_NOT_FOUND" -> Result.error(ApiCode.SUBSCRIPTION_NOT_FOUND);
                case "INVALID_PLAN", "PLAN_PRICE_NOT_CONFIGURED" ->
                        Result.error(ApiCode.INVALID_PLAN, e.getMessage());
                case "UPGRADE_REQUIRES_CHECKOUT" ->
                        Result.error(ApiCode.BAD_REQUEST, e.getMessage());
                case "INVALID_UPGRADE_TARGET", "INVALID_DOWNGRADE_TARGET", "INVALID_SUBSCRIPTION_ITEMS",
                        "SUBSCRIPTION_STATE_INVALID" ->
                        Result.error(ApiCode.SUBSCRIPTION_STATE_INVALID);
                case "STRIPE_ERROR" -> Result.error(ApiCode.STRIPE_API_ERROR, e.getMessage());
                default -> Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
            };
        }
    }

    @FunctionalInterface
    private interface SubscriptionAction {
        SubscriptionResult execute();
    }

    @Data
    static class ChangePlanRequest {
        @JsonAlias({"targetPlanCode", "newPlanCode", "plan_code", "target_plan_code", "new_plan_code"})
        private String planCode;

        String getResolvedPlanCode() {
            return planCode == null ? null : planCode.trim();
        }
    }
}
