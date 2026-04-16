package com.studyagent.api.schedule;

import com.studyagent.api.config.ReportProperties;
import com.studyagent.api.service.report.DataReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * 数据日报（每日 12:00 BJT）、周报（每周日 12:00 BJT）定时推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataReportScheduler {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private final DataReportService dataReportService;
    private final ReportProperties reportProperties;

    @Scheduled(cron = "${report.daily-cron:0 0 12 * * ?}", zone = "Asia/Shanghai")
    public void runDaily() {
        if (!reportProperties.isSchedulingEnabled()) {
            return;
        }
        LocalDate day = DataReportService.yesterdayBjt();
        log.info("DataReportScheduler: daily report for {}", day);
        dataReportService.pushDailyReportAsync(day);
    }

    @Scheduled(cron = "${report.weekly-cron:0 0 12 ? * SUN}", zone = "Asia/Shanghai")
    public void runWeekly() {
        if (!reportProperties.isSchedulingEnabled()) {
            return;
        }
        LocalDate weekEndExclusive = LocalDate.now(BJT).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        log.info("DataReportScheduler: weekly report weekEndExclusive={}", weekEndExclusive);
        dataReportService.pushWeeklyReportAsync(weekEndExclusive);
    }
}
