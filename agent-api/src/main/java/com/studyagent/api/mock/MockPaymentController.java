package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class MockPaymentController {

    private final MockAuthSupport mockAuthSupport;

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    @PostMapping("/create-checkout-session")
    public Result<Map<String, Object>> createCheckoutSession(
        @RequestBody CreateCheckoutSessionRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        PackageInfo pkg = packageInfo(request.getPackageType());
        if (pkg == null) {
            return Result.error(ApiCode.INVALID_PACKAGE_TYPE, request.getPackageType());
        }

        String sessionId = "cs_test_" + UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + 3600;

        String checkoutUrl = "http://localhost:3000/payment-success?sessionId=" + sessionId;
        if (request.getSuccessUrl() != null && !request.getSuccessUrl().isBlank()) {
            checkoutUrl = request.getSuccessUrl().replace("{CHECKOUT_SESSION_ID}", sessionId);
            if (!checkoutUrl.contains("sessionId=") && !checkoutUrl.contains("session_id=")) {
                checkoutUrl = checkoutUrl + (checkoutUrl.contains("?") ? "&" : "?") + "sessionId=" + sessionId;
            }
        }

        SessionRecord record = new SessionRecord();
        record.sessionId = sessionId;
        record.status = "complete";
        record.paymentStatus = "paid";
        record.amountTotal = pkg.amountTotal;
        record.currency = "usd";
        record.customerEmail = request.getCustomerEmail();
        record.createdAt = now;
        record.clerkUserId = request.getClerkUserId() != null && !request.getClerkUserId().isBlank()
            ? request.getClerkUserId()
            : user.uid();
        record.packageType = request.getPackageType();
        record.featureCode = featureCodeOf(request.getPackageType());
        record.credits = creditsOf(request.getPackageType());
        record.purchasedQuantity = record.credits;
        record.purchasedUnit = "task_create".equals(record.featureCode) ? "times" : "words";

        sessions.put(sessionId, record);

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", sessionId);
        data.put("checkoutUrl", checkoutUrl);
        data.put("expiresAt", expiresAt);
        return Result.success(data);
    }

    @GetMapping("/session-status")
    public Result<Map<String, Object>> getSessionStatus(
        @RequestParam(value = "sessionId", required = false) String sessionId,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Result.error(ApiCode.SESSION_ID_REQUIRED);
        }

        SessionRecord record = sessions.get(sessionId);
        if (record == null) {
            // 兜底：给出一个默认完成态，方便前端回跳页联调
            record = new SessionRecord();
            record.sessionId = sessionId;
            record.status = "complete";
            record.paymentStatus = "paid";
            record.amountTotal = 2399L;
            record.currency = "usd";
            record.customerEmail = user.email();
            record.createdAt = Instant.now().getEpochSecond();
            record.clerkUserId = user.uid();
            record.packageType = "assignment_10";
            record.featureCode = "task_create";
            record.credits = 10;
            record.purchasedQuantity = 10;
            record.purchasedUnit = "times";
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", record.sessionId);
        data.put("status", record.status);
        data.put("paymentStatus", record.paymentStatus);
        data.put("amountTotal", record.amountTotal);
        data.put("currency", record.currency);
        data.put("customerEmail", record.customerEmail);
        data.put("createdAt", record.createdAt);
        data.put("clerkUserId", record.clerkUserId);
        data.put("packageType", record.packageType);
        data.put("featureCode", record.featureCode);
        data.put("credits", record.credits);
        data.put("purchasedQuantity", record.purchasedQuantity);
        data.put("purchasedUnit", record.purchasedUnit);
        return Result.success(data);
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> getPaymentConfig(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        List<Map<String, Object>> packages = new ArrayList<>();
        packages.add(pkg("assignment_1", "1 Assignment", 1, "price_mock_assignment_1", "time", 1, "2.99", false, null));
        packages.add(pkg("assignment_5", "5 Assignments", 5, "price_mock_assignment_5", "time", 5, "12.99", false, 13));
        packages.add(pkg("assignment_10", "10 Assignments", 10, "price_mock_assignment_10", "time", 10, "23.99", true, 20));
        packages.add(pkg("assignment_50", "50 Assignments", 50, "price_mock_assignment_50", "time", 50, "99.99", false, 33));

        packages.add(pkg("ai_detection_10k", "10,000 AI Detection words", 10000, "price_mock_ai_detection_10k", "words", 10000, "1.99", false, null));
        packages.add(pkg("ai_detection_50k", "50,000 AI Detection words", 50000, "price_mock_ai_detection_50k", "words", 50000, "7.99", false, 20));
        packages.add(pkg("ai_detection_200k", "200,000 AI Detection words", 200000, "price_mock_ai_detection_200k", "words", 200000, "23.99", false, 40));

        packages.add(pkg("humanizer_10k", "10,000 Humanizer words", 10000, "price_mock_humanizer_10k", "words", 10000, "2.99", false, null));
        packages.add(pkg("humanizer_50k", "50,000 Humanizer words", 50000, "price_mock_humanizer_50k", "words", 50000, "11.99", false, 20));
        packages.add(pkg("humanizer_200k", "200,000 Humanizer words", 200000, "price_mock_humanizer_200k", "words", 200000, "39.99", false, 33));

        Map<String, Object> data = new HashMap<>();
        data.put("stripePublishableKey", "pk_test_mock_1234567890");
        data.put("packages", packages);
        return Result.success(data);
    }

    private Map<String, Object> pkg(
        String type,
        String name,
        int credits,
        String priceId,
        String unit,
        int quantity,
        String price,
        boolean popular,
        Integer savingsPercent
    ) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("name", name);
        map.put("credits", credits);
        map.put("priceId", priceId);
        map.put("unit", unit);
        map.put("quantity", quantity);
        map.put("price", price);
        map.put("popular", popular);
        map.put("savingsPercent", savingsPercent);
        return map;
    }

    private PackageInfo packageInfo(String packageType) {
        if (packageType == null) {
            return null;
        }
        return switch (packageType) {
            case "assignment_1", "starter" -> new PackageInfo(299L);
            case "assignment_5" -> new PackageInfo(1299L);
            case "assignment_10", "pro", "ai_detection_200k" -> new PackageInfo(2399L);
            case "assignment_50", "academic" -> new PackageInfo(9999L);
            case "ai_detection_10k" -> new PackageInfo(199L);
            case "ai_detection_50k" -> new PackageInfo(799L);
            case "humanizer_10k" -> new PackageInfo(299L);
            case "humanizer_50k" -> new PackageInfo(1199L);
            case "humanizer_200k" -> new PackageInfo(3999L);
            default -> null;
        };
    }

    private record PackageInfo(long amountTotal) {}

    private String featureCodeOf(String packageType) {
        if (packageType == null) {
            return null;
        }
        if (packageType.startsWith("ai_detection_")) {
            return "ai_detection";
        }
        if (packageType.startsWith("humanizer_")) {
            return "humanizer";
        }
        return "task_create";
    }

    private Integer creditsOf(String packageType) {
        if (packageType == null) {
            return null;
        }
        return switch (packageType) {
            case "assignment_1", "starter" -> 1;
            case "assignment_5" -> 5;
            case "assignment_10", "pro" -> 10;
            case "assignment_50", "academic" -> 50;
            case "ai_detection_10k", "humanizer_10k" -> 10000;
            case "ai_detection_50k", "humanizer_50k" -> 50000;
            case "ai_detection_200k", "humanizer_200k" -> 200000;
            default -> null;
        };
    }

    private static class SessionRecord {
        String sessionId;
        String status;
        String paymentStatus;
        Long amountTotal;
        String currency;
        String customerEmail;
        Long createdAt;
        String clerkUserId;
        String packageType;
        String featureCode;
        Integer credits;
        Integer purchasedQuantity;
        String purchasedUnit;
    }

    @Data
    static class CreateCheckoutSessionRequest {
        private String clerkUserId;
        private String customerEmail;
        private String packageType;
        private String successUrl;
        private String cancelUrl;
    }
}
