package com.studyagent.api.controller.internal;

import com.studyagent.api.common.Result;
import com.studyagent.api.config.ReportProperties;
import com.studyagent.api.service.report.DataReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手动触发日报/周报推送（测试用）。需配置 {@code report.manual-trigger-token}，请求头 {@code X-Report-Token}。
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/reports")
@RequiredArgsConstructor
public class DataReportTriggerController {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private final DataReportService dataReportService;
    private final ReportProperties reportProperties;

    @PostMapping("/daily")
    public Result<Map<String, String>> triggerDaily(
            @RequestHeader(value = "X-Report-Token", required = false) String token,
            @RequestParam(required = false) String date
    ) {
        if (!validateToken(token)) {
            return Result.error(403, "invalid or missing X-Report-Token");
        }
        LocalDate reportDay;
        try {
            reportDay = date != null && !date.isBlank()
                    ? LocalDate.parse(date)
                    : DataReportService.yesterdayBjt();
        } catch (DateTimeParseException e) {
            return Result.error(400, "invalid date, use yyyy-MM-dd");
        }
        log.info("Manual daily report queued: {}", reportDay);
        dataReportService.pushDailyReportAsync(reportDay);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "queued");
        data.put("reportDay", reportDay.toString());
        return Result.success(data);
    }

    /**
     * @param weekEndExclusive 统计区间右端点（不含），须为周日 00:00 的日期；不传则取「当前北京时间」最近一个星期日（含今日若今日为周日）。
     */
    @PostMapping("/weekly")
    public Result<Map<String, String>> triggerWeekly(
            @RequestHeader(value = "X-Report-Token", required = false) String token,
            @RequestParam(required = false) String weekEndExclusive
    ) {
        if (!validateToken(token)) {
            return Result.error(403, "invalid or missing X-Report-Token");
        }
        LocalDate end;
        try {
            end = weekEndExclusive != null && !weekEndExclusive.isBlank()
                    ? LocalDate.parse(weekEndExclusive)
                    : LocalDate.now(BJT).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        } catch (DateTimeParseException e) {
            return Result.error(400, "invalid weekEndExclusive, use yyyy-MM-dd");
        }
        log.info("Manual weekly report queued: weekEndExclusive={}", end);
        dataReportService.pushWeeklyReportAsync(end);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "queued");
        data.put("weekEndExclusive", end.toString());
        return Result.success(data);
    }

    private boolean validateToken(String token) {
        String expected = reportProperties.getManualTriggerToken();
        return expected != null && !expected.isBlank() && expected.equals(token);
    }
}
