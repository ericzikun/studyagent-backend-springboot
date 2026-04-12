package com.studyagent.api.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.service.robot.RobotNotifyService;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.FeedbackSubmissionEntity;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.UserMapper;
import com.studyagent.api.config.ReportProperties;
import com.studyagent.service.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从 DB 可计算的指标组装日报/周报 Markdown，并异步推送钉钉。
 * 不含：DAU、首访来源 Top5、完成→编辑、编辑→消费（需埋点/外部数仓）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataReportService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final String ORDER_COMPLETED = "completed";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TITLE_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final FeedbackSubmissionMapper feedbackSubmissionMapper;
    private final FeedbackPromptSessionMapper feedbackPromptSessionMapper;
    private final HumanizerTaskMapper humanizerTaskMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final RobotNotifyService robotNotifyService;
    private final ReportProperties reportProperties;

    @Async("robotNotifyExecutor")
    public void pushDailyReportAsync(LocalDate reportDayBjt) {
        try {
            LocalDateTime start = reportDayBjt.atStartOfDay();
            LocalDateTime end = reportDayBjt.plusDays(1).atStartOfDay();
            LocalDateTime cmpStart = start.minusDays(7);
            LocalDateTime cmpEnd = end.minusDays(7);
            TaskMetrics cur = collectTaskMetrics(start, end);
            TaskMetrics cmp = collectTaskMetrics(cmpStart, cmpEnd);
            UserMetrics uCur = collectUserMetrics(start, end);
            UserMetrics uCmp = collectUserMetrics(cmpStart, cmpEnd);
            FeedbackMetrics fCur = collectFeedbackMetrics(start, end);
            FeedbackMetrics fCmp = collectFeedbackMetrics(cmpStart, cmpEnd);

            int newRegCreated = countUsersCreatedTaskInPeriod(uCur.newUserIds, start, end);
            int newRegCreatedCmp = countUsersCreatedTaskInPeriod(uCmp.newUserIds, cmpStart, cmpEnd);
            double regToCreate = ratio(newRegCreated, uCur.newRegisterCount);
            double regToCreateCmp = ratio(newRegCreatedCmp, uCmp.newRegisterCount);

            String md = buildDailyMarkdown(reportDayBjt, start, end, cur, cmp, uCur, uCmp, fCur, fCmp, regToCreate, regToCreateCmp);
            String eventId = "daily_report_" + reportDayBjt;
            String title = "📈 " + reportProperties.getTitlePrefix() + "日报 · " + reportDayBjt.format(TITLE_DAY);
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "daily_report");
            meta.put("report_day", reportDayBjt.toString());
            robotNotifyService.dispatch(eventId, "notify.report.daily", truncateTitle(title), truncateContent(md), meta);
        } catch (Exception e) {
            log.warn("pushDailyReportAsync failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void pushWeeklyReportAsync(LocalDate weekEndExclusiveBjt) {
        try {
            LocalDateTime end = weekEndExclusiveBjt.atStartOfDay();
            LocalDateTime start = end.minusDays(7);
            LocalDateTime cmpEnd = start;
            LocalDateTime cmpStart = cmpEnd.minusDays(7);

            TaskMetrics cur = collectTaskMetrics(start, end);
            TaskMetrics cmp = collectTaskMetrics(cmpStart, cmpEnd);
            UserMetrics uCur = collectUserMetrics(start, end);
            UserMetrics uCmp = collectUserMetrics(cmpStart, cmpEnd);
            FeedbackMetrics fCur = collectFeedbackMetrics(start, end);
            FeedbackMetrics fCmp = collectFeedbackMetrics(cmpStart, cmpEnd);
            PaymentMetrics pCur = collectPaymentMetrics(start, end);
            PaymentMetrics pCmp = collectPaymentMetrics(cmpStart, cmpEnd);

            int newRegCreated = countUsersCreatedTaskInPeriod(uCur.newUserIds, start, end);
            int newRegCreatedCmp = countUsersCreatedTaskInPeriod(uCmp.newUserIds, cmpStart, cmpEnd);
            double regToCreate = ratio(newRegCreated, uCur.newRegisterCount);
            double regToCreateCmp = ratio(newRegCreatedCmp, uCmp.newRegisterCount);

            LocalDate weekStart = weekEndExclusiveBjt.minusDays(7);
            String md = buildWeeklyMarkdown(weekStart, weekEndExclusiveBjt, start, end, cur, cmp, uCur, uCmp, fCur, fCmp, pCur, pCmp, regToCreate, regToCreateCmp);
            String eventId = "weekly_report_" + weekEndExclusiveBjt;
            String title = "📈 " + reportProperties.getTitlePrefix() + "周报 · "
                    + weekStart.format(DAY_FMT) + " - " + weekEndExclusiveBjt.minusDays(1).format(DAY_FMT);
            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "weekly_report");
            meta.put("week_end_exclusive", weekEndExclusiveBjt.toString());
            robotNotifyService.dispatch(eventId, "notify.report.weekly", truncateTitle(title), truncateContent(md), meta);
        } catch (Exception e) {
            log.warn("pushWeeklyReportAsync failed: {}", e.getMessage(), e);
        }
    }

    private String buildDailyMarkdown(
            LocalDate reportDay,
            LocalDateTime start,
            LocalDateTime end,
            TaskMetrics cur,
            TaskMetrics cmp,
            UserMetrics uCur,
            UserMetrics uCmp,
            FeedbackMetrics fCur,
            FeedbackMetrics fCmp,
            double regToCreate,
            double regToCreateCmp
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 **").append(reportProperties.getTitlePrefix()).append("数据日报 · ").append(reportDay.format(TITLE_FMT)).append("**\n");
        sb.append("统计范围：**").append(reportDay.format(DAY_FMT)).append(" 00:00 - 24:00 (BJT)**\n");
        sb.append("─────────────────────────────\n");
        sb.append("👥 **访问**（DB 可算部分）\n");
        sb.append("- DAU / 新用户来源：**暂无**（需登录流水 / referrer 落库）\n");
        sb.append("- 新用户注册：**").append(uCur.newRegisterCount).append("**").append(compareInt(uCur.newRegisterCount, uCmp.newRegisterCount)).append("\n\n");

        sb.append("📝 **任务**\n");
        sb.append("- 任务创建数：**").append(cur.taskCreates).append("**").append(compareInt(cur.taskCreates, cmp.taskCreates)).append("\n");
        sb.append("- 任务成功数：**").append(cur.taskSuccess).append("**").append(compareInt(cur.taskSuccess, cmp.taskSuccess)).append("\n");
        sb.append("- 任务失败数：**").append(cur.taskFailed).append("**").append(compareInt(cur.taskFailed, cmp.taskFailed)).append("\n");
        sb.append("- 任务成功比例：**").append(pct1(cur.successRate())).append("**").append(compareRatio(cur.successRate(), cmp.successRate())).append("\n");
        sb.append("- 平均执行时长：**").append(formatDurationAvg(cur.avgCostSeconds)).append("**").append(compareRatio(cur.avgCostSeconds, cmp.avgCostSeconds)).append("\n\n");

        sb.append("🔄 **转化**（部分）\n");
        sb.append("- 注册→创建任务：**").append(pct1(regToCreate * 100)).append("**").append(compareRatio(regToCreate, regToCreateCmp)).append("\n");
        sb.append("- 创建→完成：**").append(pct1(cur.createToFinishRate())).append("**").append(compareRatio(cur.createToFinishRate() / 100.0, cmp.createToFinishRate() / 100.0)).append("\n");
        sb.append("- 完成→编辑 / 编辑→消费：**暂无**（需埋点）\n\n");

        sb.append("💬 **反馈**\n");
        appendFeedbackBlock(sb, fCur, fCmp);

        sb.append("─────────────────────────────\n");
        sb.append("对比基准：**").append(reportDay.minusDays(7).format(TITLE_FMT)).append("** 同日数据\n");
        return sb.toString();
    }

    private static final DateTimeFormatter TITLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private String buildWeeklyMarkdown(
            LocalDate weekStart,
            LocalDate weekEndExclusive,
            LocalDateTime start,
            LocalDateTime end,
            TaskMetrics cur,
            TaskMetrics cmp,
            UserMetrics uCur,
            UserMetrics uCmp,
            FeedbackMetrics fCur,
            FeedbackMetrics fCmp,
            PaymentMetrics pCur,
            PaymentMetrics pCmp,
            double regToCreate,
            double regToCreateCmp
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 **").append(reportProperties.getTitlePrefix()).append("数据周报 · ")
                .append(weekStart.format(DAY_FMT)).append(" - ").append(weekEndExclusive.minusDays(1).format(DAY_FMT)).append("**\n");
        sb.append("统计范围：**").append(weekStart.format(DAY_FMT)).append(" 00:00 - ")
                .append(weekEndExclusive.format(DAY_FMT)).append(" 00:00 (BJT)**\n");
        sb.append("─────────────────────────────\n");
        sb.append("👥 **访问**\n");
        sb.append("- DAU 均值 / 新用户来源：**暂无**\n");
        sb.append("- 新用户注册：**").append(uCur.newRegisterCount).append("**").append(compareInt(uCur.newRegisterCount, uCmp.newRegisterCount)).append("\n\n");

        sb.append("📝 **任务**\n");
        sb.append("- 任务创建数：**").append(cur.taskCreates).append("**").append(compareInt(cur.taskCreates, cmp.taskCreates)).append("\n");
        sb.append("- 任务成功数：**").append(cur.taskSuccess).append("**").append(compareInt(cur.taskSuccess, cmp.taskSuccess)).append("\n");
        sb.append("- 任务失败数：**").append(cur.taskFailed).append("**").append(compareInt(cur.taskFailed, cmp.taskFailed)).append("\n");
        sb.append("- 任务成功比例：**").append(pct1(cur.successRate())).append("**").append(compareRatio(cur.successRate(), cmp.successRate())).append("\n");
        sb.append("- 平均执行时长：**").append(formatDurationAvg(cur.avgCostSeconds)).append("**").append(compareRatio(cur.avgCostSeconds, cmp.avgCostSeconds)).append("\n\n");

        sb.append("🔄 **转化**\n");
        sb.append("- 注册→创建任务：**").append(pct1(regToCreate * 100)).append("**").append(compareRatio(regToCreate, regToCreateCmp)).append("\n");
        sb.append("- 创建→完成：**").append(pct1(cur.createToFinishRate())).append("**").append(compareRatio(cur.createToFinishRate() / 100.0, cmp.createToFinishRate() / 100.0)).append("\n");
        sb.append("- 完成→编辑 / 编辑→消费：**暂无**\n\n");

        sb.append("💰 **付费**（周）\n");
        if (pCur.nonUsdWarning) {
            sb.append("- **说明**：存在非 USD 订单，金额按 price_cents 累加展示为美元样式，**不换算汇率**。\n");
        }
        sb.append("- 当周收入：**").append(moneyUsd(pCur.totalUsdCents)).append("**").append(compareRatio(pCur.totalUsdCents, pCmp.totalUsdCents)).append("\n");
        sb.append("- 付费用户数：**").append(pCur.paidUserCount).append("**").append(compareInt(pCur.paidUserCount, pCmp.paidUserCount)).append("\n");
        sb.append("- 新付费用户占比：**").append(pct1(pCur.newPayerRatioPct())).append("**").append(compareRatio(pCur.newPayerRatioPct() / 100.0, pCmp.newPayerRatioPct() / 100.0)).append("\n");
        sb.append("- 复购用户占比：**").append(pct1(pCur.repurchasePayerRatioPct())).append("**").append(compareRatio(pCur.repurchasePayerRatioPct() / 100.0, pCmp.repurchasePayerRatioPct() / 100.0)).append("\n\n");
        appendPaymentDetail(sb, pCur);

        sb.append("💬 **反馈**\n");
        appendFeedbackBlock(sb, fCur, fCmp);

        sb.append("─────────────────────────────\n");
        sb.append("对比基准：**").append(weekStart.minusDays(7).format(DAY_FMT)).append(" 00:00 - ")
                .append(weekEndExclusive.minusDays(7).format(DAY_FMT)).append(" 00:00 (BJT)** 周数据\n");
        return sb.toString();
    }

    private void appendPaymentDetail(StringBuilder sb, PaymentMetrics p) {
        sb.append("**Assignment** 订单 **").append(p.ordersTaskCreate).append("** · ").append(moneyUsd(p.centsTaskCreate)).append("\n");
        sb.append("  ").append(skuLine(p.skuCountsTaskCreate)).append("\n");
        sb.append("**AI Detection** 订单 **").append(p.ordersAiDetection).append("** · ").append(moneyUsd(p.centsAiDetection)).append("\n");
        sb.append("  ").append(skuLine(p.skuCountsAiDetection)).append("\n");
        sb.append("**Humanizer** 订单 **").append(p.ordersHumanizer).append("** · ").append(moneyUsd(p.centsHumanizer)).append("\n");
        sb.append("  ").append(skuLine(p.skuCountsHumanizer)).append("\n");
        sb.append("各功能收入占比：\n");
        long total = p.centsTaskCreate + p.centsAiDetection + p.centsHumanizer;
        sb.append("  Assignment · ").append(moneyUsd(p.centsTaskCreate)).append("（").append(pctOf(total, p.centsTaskCreate)).append("）\n");
        sb.append("  AI Detection · ").append(moneyUsd(p.centsAiDetection)).append("（").append(pctOf(total, p.centsAiDetection)).append("）\n");
        sb.append("  Humanizer · ").append(moneyUsd(p.centsHumanizer)).append("（").append(pctOf(total, p.centsHumanizer)).append("）\n\n");
    }

    private static String skuLine(Map<String, Integer> skuCounts) {
        if (skuCounts == null || skuCounts.isEmpty()) {
            return "—";
        }
        return skuCounts.entrySet().stream()
                .map(e -> e.getKey() + " ×" + e.getValue())
                .collect(Collectors.joining(" / "));
    }

    private void appendFeedbackBlock(StringBuilder sb, FeedbackMetrics cur, FeedbackMetrics cmp) {
        sb.append("- 反馈数 Assignment / AI Detection / Humanizer：**")
                .append(cur.assignmentCount).append(" / ").append(cur.detectionCount).append(" / ").append(cur.humanizerCount).append("**");
        sb.append("（环比 Assignment ").append(trimParen(compareInt(cur.assignmentCount, cmp.assignmentCount)))
                .append(" · Detection ").append(trimParen(compareInt(cur.detectionCount, cmp.detectionCount)))
                .append(" · Humanizer ").append(trimParen(compareInt(cur.humanizerCount, cmp.humanizerCount))).append("）\n");
        sb.append("- Assignment 平均评分：**").append(cur.assignmentAvgScore == null ? "—" : String.format(Locale.US, "%.1f 星", cur.assignmentAvgScore));
        sb.append("**").append(compareDouble(cur.assignmentAvgScore, cmp.assignmentAvgScore)).append("\n");
        sb.append("- AI Detection 好评率：**").append(pct1(cur.detectionThumbRate())).append("**").append(compareRatio(cur.detectionThumbRate(), cmp.detectionThumbRate())).append("\n");
        sb.append("- Humanizer 好评率：**").append(pct1(cur.humanizerThumbRate())).append("**").append(compareRatio(cur.humanizerThumbRate(), cmp.humanizerThumbRate())).append("\n\n");
    }

    private TaskMetrics collectTaskMetrics(LocalDateTime start, LocalDateTime end) {
        Long creates = taskMapper.selectCount(
                new LambdaQueryWrapper<TaskEntity>()
                        .ge(TaskEntity::getCreatedAt, start)
                        .lt(TaskEntity::getCreatedAt, end)
                        .ne(TaskEntity::getStatus, TaskStatus.DRAFT.getCode())
                        .eq(TaskEntity::getIsDeleted, 0));
        Long success = taskMapper.selectCount(
                new LambdaQueryWrapper<TaskEntity>()
                        .eq(TaskEntity::getStatus, TaskStatus.COMPLETED.getCode())
                        .ge(TaskEntity::getFinishTime, start)
                        .lt(TaskEntity::getFinishTime, end)
                        .eq(TaskEntity::getIsDeleted, 0));
        Long failed = taskMapper.selectCount(
                new LambdaQueryWrapper<TaskEntity>()
                        .eq(TaskEntity::getStatus, TaskStatus.FAILED.getCode())
                        .ge(TaskEntity::getFinishTime, start)
                        .lt(TaskEntity::getFinishTime, end)
                        .eq(TaskEntity::getIsDeleted, 0));

        List<TaskEntity> completed = taskMapper.selectList(
                new LambdaQueryWrapper<TaskEntity>()
                        .eq(TaskEntity::getStatus, TaskStatus.COMPLETED.getCode())
                        .ge(TaskEntity::getFinishTime, start)
                        .lt(TaskEntity::getFinishTime, end)
                        .eq(TaskEntity::getIsDeleted, 0));

        double avgSec = completed.stream()
                .mapToInt(t -> t.getCostTime() != null && t.getCostTime() > 0 ? t.getCostTime() : 0)
                .average()
                .orElse(0);

        TaskMetrics m = new TaskMetrics();
        m.taskCreates = creates != null ? creates.intValue() : 0;
        m.taskSuccess = success != null ? success.intValue() : 0;
        m.taskFailed = failed != null ? failed.intValue() : 0;
        m.avgCostSeconds = avgSec;
        return m;
    }

    private UserMetrics collectUserMetrics(LocalDateTime start, LocalDateTime end) {
        List<UserProfileEntity> users = userMapper.selectList(
                new LambdaQueryWrapper<UserProfileEntity>()
                        .ge(UserProfileEntity::getCreatedAt, start)
                        .lt(UserProfileEntity::getCreatedAt, end));
        UserMetrics u = new UserMetrics();
        u.newRegisterCount = users.size();
        u.newUserIds = users.stream().map(UserProfileEntity::getClerkUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        return u;
    }

    private int countUsersCreatedTaskInPeriod(Set<String> newUserIds, LocalDateTime start, LocalDateTime end) {
        if (newUserIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String uid : newUserIds) {
            Long c = taskMapper.selectCount(
                    new LambdaQueryWrapper<TaskEntity>()
                            .eq(TaskEntity::getClerkUserId, uid)
                            .ge(TaskEntity::getCreatedAt, start)
                            .lt(TaskEntity::getCreatedAt, end)
                            .ne(TaskEntity::getStatus, TaskStatus.DRAFT.getCode())
                            .eq(TaskEntity::getIsDeleted, 0));
            if (c != null && c > 0) {
                n++;
            }
        }
        return n;
    }

    private FeedbackMetrics collectFeedbackMetrics(LocalDateTime start, LocalDateTime end) {
        List<FeedbackSubmissionEntity> subs = feedbackSubmissionMapper.selectList(
                new LambdaQueryWrapper<FeedbackSubmissionEntity>()
                        .ge(FeedbackSubmissionEntity::getCreatedAt, start)
                        .lt(FeedbackSubmissionEntity::getCreatedAt, end));
        if (subs.isEmpty()) {
            return new FeedbackMetrics();
        }
        Set<String> pids = subs.stream().map(FeedbackSubmissionEntity::getPromptSessionId).collect(Collectors.toSet());
        List<FeedbackPromptSessionEntity> sessions = feedbackPromptSessionMapper.selectList(
                new LambdaQueryWrapper<FeedbackPromptSessionEntity>()
                        .in(FeedbackPromptSessionEntity::getPromptSessionId, pids));
        Map<String, FeedbackPromptSessionEntity> byPid = sessions.stream()
                .collect(Collectors.toMap(FeedbackPromptSessionEntity::getPromptSessionId, s -> s, (a, b) -> a));

        FeedbackMetrics m = new FeedbackMetrics();
        double scoreSum = 0;
        int scoreCnt = 0;
        int detVotes = 0;
        int detUp = 0;
        int humVotes = 0;
        int humUp = 0;

        for (FeedbackSubmissionEntity s : subs) {
            FeedbackPromptSessionEntity p = byPid.get(s.getPromptSessionId());
            if (p == null) {
                continue;
            }
            if ("task".equals(p.getSubjectType())) {
                m.assignmentCount++;
                if ("rating".equals(p.getVariant()) && s.getScore() != null) {
                    scoreSum += s.getScore();
                    scoreCnt++;
                }
            } else if ("humanizer_task".equals(p.getSubjectType())) {
                Long hid = parseSubjectId(p.getSubjectId());
                if (hid == null) {
                    continue;
                }
                HumanizerTaskEntity ht = humanizerTaskMapper.selectById(hid);
                if (ht == null) {
                    continue;
                }
                if ("DETECT".equals(ht.getTaskType())) {
                    m.detectionCount++;
                    if ("thumb".equals(p.getVariant()) && s.getVote() != null) {
                        detVotes++;
                        if ("up".equals(s.getVote())) {
                            detUp++;
                        }
                    }
                } else {
                    m.humanizerCount++;
                    if ("thumb".equals(p.getVariant()) && s.getVote() != null) {
                        humVotes++;
                        if ("up".equals(s.getVote())) {
                            humUp++;
                        }
                    }
                }
            }
        }
        if (scoreCnt > 0) {
            m.assignmentAvgScore = scoreSum / scoreCnt;
        }
        m.detectionThumbUp = detUp;
        m.detectionThumbTotal = detVotes;
        m.humanizerThumbUp = humUp;
        m.humanizerThumbTotal = humVotes;
        return m;
    }

    private static Long parseSubjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return TaskIdEncoder.decode(s);
        }
    }

    private PaymentMetrics collectPaymentMetrics(LocalDateTime start, LocalDateTime end) {
        List<RechargeOrderEntity> list = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED)
                        .ge(RechargeOrderEntity::getPaidAt, start)
                        .lt(RechargeOrderEntity::getPaidAt, end));

        PaymentMetrics p = new PaymentMetrics();
        Set<String> paidUsers = new HashSet<>();
        Map<String, LocalDateTime> firstPaidEver = loadFirstPaidEver(list);

        for (RechargeOrderEntity o : list) {
            if (o.getClerkUserId() != null) {
                paidUsers.add(o.getClerkUserId());
            }
            int cents = o.getPriceCents() != null ? o.getPriceCents() : 0;
            if (!"usd".equalsIgnoreCase(String.valueOf(o.getCurrency()))) {
                p.nonUsdWarning = true;
            }
            p.totalUsdCents += cents;
            String fc = o.getFeatureCode() != null ? o.getFeatureCode() : "";
            String pkg = o.getPackageCode() != null ? o.getPackageCode() : "unknown";
            if (FeatureCode.TASK_CREATE.getCode().equals(fc)) {
                p.ordersTaskCreate++;
                p.centsTaskCreate += cents;
                p.skuCountsTaskCreate.merge(pkg, 1, Integer::sum);
            } else if (FeatureCode.AI_DETECTION.getCode().equals(fc)) {
                p.ordersAiDetection++;
                p.centsAiDetection += cents;
                p.skuCountsAiDetection.merge(pkg, 1, Integer::sum);
            } else if (FeatureCode.HUMANIZER.getCode().equals(fc)) {
                p.ordersHumanizer++;
                p.centsHumanizer += cents;
                p.skuCountsHumanizer.merge(pkg, 1, Integer::sum);
            }
        }
        p.paidUserCount = paidUsers.size();
        int newPayers = 0;
        int repurchase = 0;
        for (String uid : paidUsers) {
            LocalDateTime first = firstPaidEver.get(uid);
            boolean firstTimeInWindow = first != null && !first.isBefore(start) && first.isBefore(end);
            if (firstTimeInWindow) {
                newPayers++;
            }
            boolean hadBefore = first != null && first.isBefore(start);
            if (hadBefore) {
                repurchase++;
            }
        }
        p.newPayerCount = newPayers;
        p.repurchaseUserCount = repurchase;
        return p;
    }

    /** 每个用户在窗口内订单之前，历史上首次成功付费时间（仅对窗口内出现过的用户查询） */
    private Map<String, LocalDateTime> loadFirstPaidEver(List<RechargeOrderEntity> windowOrders) {
        Set<String> uids = windowOrders.stream().map(RechargeOrderEntity::getClerkUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, LocalDateTime> first = new HashMap<>();
        for (String uid : uids) {
            RechargeOrderEntity earliest = rechargeOrderMapper.selectOne(
                    new LambdaQueryWrapper<RechargeOrderEntity>()
                            .eq(RechargeOrderEntity::getClerkUserId, uid)
                            .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED)
                            .isNotNull(RechargeOrderEntity::getPaidAt)
                            .orderByAsc(RechargeOrderEntity::getPaidAt)
                            .last("LIMIT 1"));
            if (earliest != null && earliest.getPaidAt() != null) {
                first.put(uid, earliest.getPaidAt());
            }
        }
        return first;
    }

    private static double ratio(int num, int den) {
        if (den <= 0) {
            return 0;
        }
        return (double) num / (double) den;
    }

    private static String pct1(double v) {
        return String.format(Locale.US, "%.1f%%", v);
    }

    private static String pctOf(long total, long part) {
        if (total <= 0) {
            return "—";
        }
        return String.format(Locale.US, "%.1f%%", 100.0 * part / total);
    }

    private static String moneyUsd(long cents) {
        BigDecimal b = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return "$" + b;
    }

    private static String formatDurationAvg(double avgSec) {
        if (avgSec <= 0) {
            return "—";
        }
        int sec = (int) Math.round(avgSec);
        int m = sec / 60;
        int s = sec % 60;
        return String.format(Locale.US, "%d分%02d秒", m, s);
    }

    private static String compareInt(int cur, int prev) {
        if (prev == 0) {
            return "（无基准）";
        }
        double pct = (cur - prev) / (double) prev * 100.0;
        return String.format(Locale.US, "（%+.1f%%）", pct);
    }

    private static String compareRatio(double cur, double prev) {
        if (prev <= 0 && cur <= 0) {
            return "（无基准）";
        }
        if (prev <= 0) {
            return "（无基准）";
        }
        double pct = (cur - prev) / prev * 100.0;
        return String.format(Locale.US, "（%+.1f%%）", pct);
    }

    private static String compareDouble(Double cur, Double prev) {
        if (cur == null || prev == null || prev == 0) {
            return "（无基准）";
        }
        double d = cur - prev;
        return String.format(Locale.US, "（%+.1f）", d);
    }

    private static String trimParen(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("（") && t.endsWith("）")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String truncateTitle(String t) {
        return t.length() <= 80 ? t : t.substring(0, 79) + "…";
    }

    private static String truncateContent(String c) {
        return c.length() <= 2000 ? c : c.substring(0, 1999) + "…";
    }

    /** 供调度器取「昨日」 */
    public static LocalDate yesterdayBjt() {
        return LocalDate.now(BJT).minusDays(1);
    }

    private static final class TaskMetrics {
        int taskCreates;
        int taskSuccess;
        int taskFailed;
        double avgCostSeconds;

        double successRate() {
            return taskCreates <= 0 ? 0 : 100.0 * taskSuccess / taskCreates;
        }

        double createToFinishRate() {
            return successRate();
        }
    }

    private static final class UserMetrics {
        int newRegisterCount;
        Set<String> newUserIds = new HashSet<>();
    }

    private static final class FeedbackMetrics {
        int assignmentCount;
        int detectionCount;
        int humanizerCount;
        Double assignmentAvgScore;
        int detectionThumbUp;
        int detectionThumbTotal;
        int humanizerThumbUp;
        int humanizerThumbTotal;

        double detectionThumbRate() {
            return detectionThumbTotal <= 0 ? 0 : 100.0 * detectionThumbUp / detectionThumbTotal;
        }

        double humanizerThumbRate() {
            return humanizerThumbTotal <= 0 ? 0 : 100.0 * humanizerThumbUp / humanizerThumbTotal;
        }
    }

    private static final class PaymentMetrics {
        long totalUsdCents;
        int paidUserCount;
        int newPayerCount;
        int repurchaseUserCount;
        int ordersTaskCreate;
        int ordersAiDetection;
        int ordersHumanizer;
        long centsTaskCreate;
        long centsAiDetection;
        long centsHumanizer;
        Map<String, Integer> skuCountsTaskCreate = new HashMap<>();
        Map<String, Integer> skuCountsAiDetection = new HashMap<>();
        Map<String, Integer> skuCountsHumanizer = new HashMap<>();
        boolean nonUsdWarning;

        double newPayerRatioPct() {
            return paidUserCount <= 0 ? 0 : 100.0 * newPayerCount / paidUserCount;
        }

        double repurchasePayerRatioPct() {
            return paidUserCount <= 0 ? 0 : 100.0 * repurchaseUserCount / paidUserCount;
        }
    }
}
