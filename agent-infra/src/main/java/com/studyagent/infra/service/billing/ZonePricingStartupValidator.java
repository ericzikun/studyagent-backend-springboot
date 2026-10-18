package com.studyagent.infra.service.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时离线结构校验（只查 DB，不打 Stripe）。违例只告警不阻断启动：
 * 全部开关默认关，草稿行（price_id 未回填）在未启用范围内合法。
 * 远程 Stripe 交叉核对走 preflight 内部端点按需执行，不在启动时跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZonePricingStartupValidator {

    private final ZonePricingCatalog catalog;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        try {
            java.util.List<String> violations = catalog.structuralViolations();
            if (violations.isEmpty()) {
                log.info("zone pricing catalog structural check passed");
            } else {
                log.warn("zone pricing catalog structural violations ({}): {}",
                        violations.size(), violations);
            }
        } catch (Exception ex) {
            log.error("zone pricing catalog structural check failed to run: {}", ex.getMessage());
        }
    }
}
