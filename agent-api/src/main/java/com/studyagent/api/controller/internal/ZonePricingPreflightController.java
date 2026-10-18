package com.studyagent.api.controller.internal;

import com.studyagent.api.common.Result;
import com.studyagent.infra.service.billing.ZonePricingPreflightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 区域定价上线前检入口（只读）。核对逻辑在 {@link ZonePricingPreflightService}。
 * 请求头 {@code X-Internal-Token}（{@code billing.zone-pricing.internal-token}，默认空=拒绝）。
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/billing/zone-pricing")
@RequiredArgsConstructor
public class ZonePricingPreflightController {

    private final ZonePricingPreflightService preflightService;

    @Value("${billing.zone-pricing.internal-token:}")
    private String internalToken;

    /**
     * @param zone  可选，只核对单个区（调试用）；缺省核对全部绑定
     * @param fresh true 时绕过 Stripe Price 网关缓存（默认 true，上线前检必须读实时值）
     */
    @PostMapping("/preflight")
    public Result<Map<String, Object>> preflight(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(required = false) String zone,
            @RequestParam(defaultValue = "true") boolean fresh) {
        if (!authorized(token)) {
            return Result.error(403, "invalid or missing X-Internal-Token");
        }
        ZonePricingPreflightService.Result result = preflightService.run(zone, fresh);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        data.put("stripeMode", result.stripeMode());
        data.put("zoneFilter", result.zoneFilter());
        data.put("structuralViolations", result.structuralViolations());
        data.put("summary", result.summary());
        data.put("items", result.items());
        return Result.success(data);
    }

    private boolean authorized(String token) {
        return internalToken != null && !internalToken.isBlank()
                && internalToken.equals(token);
    }
}
