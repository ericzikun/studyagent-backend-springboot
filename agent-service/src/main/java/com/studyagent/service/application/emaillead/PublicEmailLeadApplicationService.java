package com.studyagent.service.application.emaillead;

import com.studyagent.service.domain.emaillead.PublicEmailLeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 公开邮箱留资用例。
 *
 * <p>负责蜜罐短路、邮箱与来源规范化、写入保护和幂等落库；不负责发信、订阅状态或营销同意管理。</p>
 */
@Service
@RequiredArgsConstructor
public class PublicEmailLeadApplicationService {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_SOURCE_LENGTH = 191;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^/(?:tools|use-cases|tools/[a-z0-9]+(?:-[a-z0-9]+)*)$");

    private final PublicEmailLeadRepository repository;
    private final PublicEmailLeadWriteGuard writeGuard;

    /**
     * 接收一次匿名邮箱留资；重复提交不会覆盖首次来源。
     */
    public void capture(String email, String source, String companyWebsite, String clientIp) {
        // 蜜罐有值时伪装成成功，避免向机器人暴露拦截规则，同时不触碰 Redis、MySQL 或埋点。
        if (companyWebsite != null && !companyWebsite.isBlank()) {
            return;
        }

        String normalizedEmail = normalizeAndValidateEmail(email);
        String sourcePath = validateSource(source);

        writeGuard.checkIpRateLimit(clientIp);
        // 已存在的邮箱无需竞争当日新增名额；唯一索引仍负责解决该查询之后的并发首次提交。
        if (repository.existsByNormalizedEmail(normalizedEmail)) {
            return;
        }

        writeGuard.reserveDailyNew();
        boolean inserted;
        try {
            inserted = repository.insertIfAbsent(normalizedEmail, sourcePath, LocalDateTime.now());
        } catch (RuntimeException ex) {
            releaseAfterFailedWrite(ex);
            throw ex;
        }

        if (!inserted) {
            writeGuard.releaseDailyReservation();
        }
    }

    private String normalizeAndValidateEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("email is invalid");
        }
        return normalized;
    }

    private String validateSource(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        String sourcePath = source.trim();
        if (sourcePath.length() > MAX_SOURCE_LENGTH || !SOURCE_PATTERN.matcher(sourcePath).matches()) {
            throw new IllegalArgumentException("source is invalid");
        }
        return sourcePath;
    }

    private void releaseAfterFailedWrite(RuntimeException writeFailure) {
        try {
            writeGuard.releaseDailyReservation();
        } catch (RuntimeException releaseFailure) {
            writeFailure.addSuppressed(releaseFailure);
        }
    }
}
