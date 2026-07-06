package com.studyagent.api.service.robot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.studyagent.common.datetime.DateTimeFormats;
import com.studyagent.common.quota.FeatureCode;
import com.studyagent.infra.entity.FeedbackPromptSessionEntity;
import com.studyagent.infra.entity.FeedbackSubmissionEntity;
import com.studyagent.infra.entity.FileEntity;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskFileEntity;
import com.studyagent.infra.entity.UserProfileEntity;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import com.studyagent.infra.mapper.FeedbackPromptSessionMapper;
import com.studyagent.infra.mapper.FeedbackSubmissionMapper;
import com.studyagent.infra.mapper.FileMapper;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.infra.mapper.TaskFileMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.UserMapper;
import com.studyagent.infra.mapper.verla.VerlaConversationMapper;
import com.studyagent.api.util.TaskIdEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 异步组装机器人推送文案并调用 {@link RobotNotifyService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotNotifyAsyncService {

    private static final String ORDER_COMPLETED = "completed";
    private static final int TASK_STATUS_COMPLETED = 3;
    private static final String PREFIX_VERLA_CONVERSATION_SUBJECT = "verla_conversation:";
    private final RobotNotifyService robotNotifyService;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final HumanizerTaskMapper humanizerTaskMapper;
    private final TaskFileMapper taskFileMapper;
    private final FileMapper fileMapper;
    private final FeedbackPromptSessionMapper feedbackPromptSessionMapper;
    private final FeedbackSubmissionMapper feedbackSubmissionMapper;
    private final VerlaConversationMapper verlaConversationMapper;

    @Value("${app.public-site-url:http://localhost:3000}")
    private String publicSiteUrl;

    @Async("robotNotifyExecutor")
    public void notifyPaymentSucceeded(
            String stripeEventId,
            String sessionId,
            String clerkUserId,
            String featureCode,
            String packageType,
            long quotaAmount,
            int priceCents,
            String currency,
            String customerEmail,
            String paymentIntentId
    ) {
        try {
            String title = buildPaymentTitle(featureCode, "付款成功", isRepurchase(clerkUserId, featureCode, sessionId));
            String md = buildPaymentSuccessMarkdown(
                    stripeEventId,
                    sessionId,
                    clerkUserId,
                    featureCode,
                    packageType,
                    quotaAmount,
                    priceCents,
                    currency,
                    customerEmail,
                    paymentIntentId
            );
            Map<String, Object> meta = baseMeta("payment_success", stripeEventId, clerkUserId, featureCode);
            meta.put("stripe_session_id", sessionId);
            robotNotifyService.dispatch(RobotNotifyRouteKind.ASSIGNMENT, stripeEventId, "notify.payment.success", title, truncate(md, 2000), meta);
        } catch (Exception e) {
            log.warn("notifyPaymentSucceeded failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyPaymentFailed(
            String notifyEventId,
            String clerkUserId,
            String featureCode,
            String packageType,
            long quotaAmount,
            int priceCents,
            String currency,
            String paymentIntentId,
            String failureReason,
            String stripeEventType
    ) {
        try {
            String title = buildPaymentTitle(featureCode, "付款失败", isRepurchase(clerkUserId, featureCode, null));
            StringBuilder sb = new StringBuilder();
            sb.append("**Stripe 事件**: ").append(stripeEventType != null ? stripeEventType : "payment_failed").append("\n\n");
            sb.append("- **功能**: ").append(featureDisplay(featureCode)).append("\n");
            sb.append("- **套餐**: ").append(formatPackageLine(quotaAmount, priceCents, currency, packageType, featureCode)).append("\n");
            sb.append("- **UID**: ").append(nullToDash(clerkUserId)).append("\n");
            sb.append("- **邮箱(Stripe)**: ").append("—").append("\n");
            sb.append("- **失败原因**: ").append(nullToDash(failureReason)).append("\n");
            sb.append("- **PaymentIntent**: ").append(nullToDash(paymentIntentId)).append("\n");
            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
            Map<String, Object> meta = baseMeta("payment_failed", notifyEventId, clerkUserId, featureCode);
            meta.put("payment_intent_id", paymentIntentId);
            robotNotifyService.dispatch(RobotNotifyRouteKind.ASSIGNMENT, notifyEventId, "notify.payment.failed", title, truncate(sb.toString(), 2000), meta);
        } catch (Exception e) {
            log.warn("notifyPaymentFailed failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyCheckoutExpired(
            String stripeEventId,
            String sessionId,
            String clerkUserId,
            String featureCode,
            String packageType,
            long quotaAmount,
            int priceCents,
            String currency
    ) {
        try {
            String title = buildPaymentTitle(
                    featureCode != null ? featureCode : FeatureCode.TASK_CREATE.getCode(),
                    "退出付款",
                    isRepurchase(clerkUserId, featureCode != null ? featureCode : FeatureCode.TASK_CREATE.getCode(), null)
            );
            StringBuilder sb = new StringBuilder();
            sb.append("**Stripe 事件**: checkout.session.expired\n\n");
            sb.append("- **功能**: ").append(featureDisplay(featureCode)).append("\n");
            sb.append("- **套餐**: ").append(formatPackageLine(quotaAmount, priceCents, currency, packageType, featureCode)).append("\n");
            sb.append("- **UID**: ").append(nullToDash(clerkUserId)).append("\n");
            sb.append("- **来源**: ").append("未追踪（需在创建 Checkout 时写入 metadata.purchase_source）").append("\n");
            sb.append("- **Session**: ").append(sessionId).append("\n");
            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
            Map<String, Object> meta = baseMeta("checkout_expired", stripeEventId, clerkUserId, featureCode);
            meta.put("stripe_session_id", sessionId);
            robotNotifyService.dispatch(RobotNotifyRouteKind.ASSIGNMENT, stripeEventId, "notify.checkout.expired", title, truncate(sb.toString(), 2000), meta);
        } catch (Exception e) {
            log.warn("notifyCheckoutExpired failed: {}", e.getMessage(), e);
        }
    }

    @Async("robotNotifyExecutor")
    public void notifyFeedbackSubmitted(String clerkUserId, String promptSessionId, String submissionId) {
        try {
            FeedbackPromptSessionEntity session = feedbackPromptSessionMapper.selectOne(
                    new LambdaQueryWrapper<FeedbackPromptSessionEntity>()
                            .eq(FeedbackPromptSessionEntity::getPromptSessionId, promptSessionId)
                            .last("LIMIT 1"));
            FeedbackSubmissionEntity sub = feedbackSubmissionMapper.selectOne(
                    new LambdaQueryWrapper<FeedbackSubmissionEntity>()
                            .eq(FeedbackSubmissionEntity::getSubmissionId, submissionId)
                            .last("LIMIT 1"));
            if (session == null || sub == null) {
                log.warn("feedback notify skip: session or submission missing");
                return;
            }

            Long subjectNumericId = parseFeedbackSubjectId(session.getSubjectId());

            String featureLabel;
            String titlePrefix;
            if ("task".equals(session.getSubjectType())) {
                featureLabel = "Assignment";
                titlePrefix = "Assignment 用户反馈";
            } else if ("humanizer_task".equals(session.getSubjectType())) {
                HumanizerTaskEntity ht0 = subjectNumericId != null ? humanizerTaskMapper.selectById(subjectNumericId) : null;
                if (ht0 != null && "DETECT".equals(ht0.getTaskType())) {
                    featureLabel = "AI Detection";
                    titlePrefix = "AI Detection 用户反馈";
                } else {
                    featureLabel = "Humanizer";
                    titlePrefix = "Humanizer 用户反馈";
                }
            } else {
                featureLabel = session.getSubjectType();
                titlePrefix = "用户反馈";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("- **功能**: ").append(featureLabel).append("\n");
            if ("rating".equals(session.getVariant()) && sub.getScore() != null) {
                sb.append("- **评分**: ").append(sub.getScore()).append(" 星\n");
            } else if ("thumb".equals(session.getVariant())) {
                sb.append("- **评价**: ").append("up".equals(sub.getVote()) ? "好评" : ("down".equals(sub.getVote()) ? "差评" : nullToDash(sub.getVote()))).append("\n");
            }
            sb.append("- **标签**: ").append(nullToDash(sub.getSelectedTagCodesJson())).append("\n");
            sb.append("- **自定义评价**: ").append(blankToUserSkipped(sub.getComment())).append("\n");
            sb.append("- **联系方式**: ").append(blankToUserSkipped(sub.getContact())).append("\n");
            sb.append("- **UID**: ").append(nullToDash(clerkUserId)).append("\n");
            sb.append("- **邮箱**: ").append("未入库（user_profiles 无 email）").append("\n");
            sb.append("- **国家 / 首访来源**: ").append("未入库").append("\n");
            sb.append("- **反馈时机**: ").append(feedbackTimingCn(session.getTriggerCode())).append("\n");

            if ("task".equals(session.getSubjectType())) {
                Long verlaConversationId = parseVerlaConversationSubjectId(session.getSubjectId());
                if (verlaConversationId != null) {
                    appendVerlaConversationFeedback(sb, clerkUserId, verlaConversationId);
                } else if (subjectNumericId == null) {
                    sb.append("- **任务**: subject_id 无法解析（非数字且非 Sqids）: ").append(nullToDash(session.getSubjectId())).append("\n");
                } else {
                    long taskId = subjectNumericId;
                    TaskEntity task = taskMapper.selectById(taskId);
                    if (task != null) {
                        boolean oldUser = hasPriorCompletedTask(clerkUserId, taskId);
                        sb.append("- **用户类型**: ").append(oldUser ? "老用户" : "新用户").append("\n");
                        sb.append("- **任务链接**: ").append(editorLink(taskId)).append("\n");
                        sb.append("- **上传文件**: ").append(formatTaskFileDistribution(taskId)).append("\n");
                        sb.append("- **用户Query**: ").append(truncateWords(task.getTaskDesc(), 100)).append("\n");
                        sb.append("- **Subject**: ").append(TaskFieldDisplay.subject(task.getSubject())).append("\n");
                        sb.append("- **Academic Level**: ").append(TaskFieldDisplay.academicLevel(task.getAcademicLevel())).append("\n");
                        sb.append("- **Citation**: ").append(TaskFieldDisplay.citationStyle(task.getCitationStyle())).append("\n");
                        sb.append("- **Estimated Length**: ").append(TaskFieldDisplay.pageLength(task.getPageLength())).append("\n");
                        sb.append("- **任务执行时长**: ").append(formatSecondsAsMmSs(task.getCostTime())).append("\n");
                        sb.append("- **追问Q&A**: ").append(extractClarifyingSnippet(task.getRequirementJson())).append("\n");
                    } else {
                        sb.append("- **任务**: 未找到任务记录 id=").append(taskId).append("\n");
                    }
                }
            } else if ("humanizer_task".equals(session.getSubjectType())) {
                if (subjectNumericId == null) {
                    sb.append("- **任务**: subject_id 无法解析: ").append(nullToDash(session.getSubjectId())).append("\n");
                } else {
                    long hid = subjectNumericId;
                    HumanizerTaskEntity ht = humanizerTaskMapper.selectById(hid);
                    if (ht != null) {
                        sb.append("- **任务链接**: ").append(humanizerPageNote(hid)).append("\n");
                        if ("DETECT".equals(ht.getTaskType())) {
                            sb.append("- **Detection 耗时**: ").append(ht.getElapsedSeconds() != null ? String.format(Locale.US, "%.0f 秒", ht.getElapsedSeconds()) : "—").append("\n");
                            sb.append("- **Detection words**: ").append(ht.getTotalWords() != null ? ht.getTotalWords() : "—").append("\n");
                            sb.append("- **AI 概率**: ").append(ht.getProbability() != null ? String.format(Locale.US, "%.2f%%", ht.getProbability() * 100) : "—").append("\n");
                        } else {
                            sb.append("- **Humanizer 耗时**: ").append(ht.getElapsedSeconds() != null ? String.format(Locale.US, "%.0f 秒", ht.getElapsedSeconds()) : "—").append("\n");
                            sb.append("- **Humanizer words**: ").append(ht.getTotalWords() != null ? ht.getTotalWords() : "—").append("\n");
                        }
                        sb.append("- **入口**: ").append(mapHumanizerSource(ht.getSource())).append("\n");
                        boolean oldHu = hasPriorHumanizerCompleted(clerkUserId, hid, ht.getTaskType());
                        sb.append("- **用户类型**: ").append(oldHu ? "老用户" : "新用户").append("\n");
                    } else {
                        sb.append("- **任务**: 未找到 humanizer 任务 id=").append(hid).append("\n");
                    }
                }
            }

            sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");

            Map<String, Object> meta = new HashMap<>();
            meta.put("kind", "feedback");
            meta.put("submission_id", submissionId);
            meta.put("prompt_session_id", promptSessionId);
            meta.put("clerk_user_id", clerkUserId);

            String title = "[" + titlePrefix + "] " + submissionId;
            robotNotifyService.dispatch(
                    RobotNotifyRouteKind.FEEDBACK,
                    "feedback_" + submissionId,
                    "notify.feedback.submitted",
                    truncate(title, 80),
                    truncate(sb.toString(), 2000),
                    meta
            );
        } catch (Exception e) {
            log.warn("notifyFeedbackSubmitted failed: {}", e.getMessage(), e);
        }
    }

    private String buildPaymentTitle(String featureCode, String paymentState, boolean repurchase) {
        String f = featureDisplay(featureCode);
        String rp = repurchase ? "复购" : "首购";
        return String.format(Locale.US, "[付费播报] %s · %s · %s", f, paymentState, rp);
    }

    private String buildPaymentSuccessMarkdown(
            String stripeEventId,
            String sessionId,
            String clerkUserId,
            String featureCode,
            String packageType,
            long quotaAmount,
            int priceCents,
            String currency,
            String customerEmail,
            String paymentIntentId
    ) {
        boolean repurchase = isRepurchase(clerkUserId, featureCode, sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("**Stripe 事件**: checkout.session.completed\n\n");
        sb.append("- **功能 · 状态 · 首复购**: ").append(featureDisplay(featureCode)).append(" · 付款成功 · ").append(repurchase ? "复购" : "首购").append("\n");
        sb.append("- **套餐**: ").append(formatPackageLine(quotaAmount, priceCents, currency, packageType, featureCode)).append("\n");
        sb.append("- **来源**: ").append("未追踪（需在 Checkout metadata 增加 purchase_source）").append("\n");
        sb.append("- **UID**: ").append(nullToDash(clerkUserId)).append("\n");
        sb.append("- **邮箱(Stripe/会话)**: ").append(nullToDash(customerEmail)).append("\n");
        sb.append("- **国家 / 首访**: ").append("未入库").append("\n");
        sb.append("- **上次任务相关**: ").append(lastTaskSummary(clerkUserId, featureCode)).append("\n");
        if (repurchase) {
            sb.append("- **复购间隔(天)**: ").append(repurchaseGapDays(clerkUserId, featureCode, sessionId)).append("\n");
            sb.append("- **累计付费(本功能,含本次)**: ").append(sumCompletedUsd(featureCode, clerkUserId)).append("\n");
        }
        sb.append("- **各功能月均(口径见文档)**: ").append(monthlyPaySummary(clerkUserId)).append("\n");
        sb.append("- **上次任务链接**: ").append(lastTaskLink(clerkUserId, featureCode)).append("\n");
        sb.append("- **时间(北京时间)**: ").append(appNowLabel()).append("\n");
        sb.append("- **Stripe**: session=").append(sessionId).append(", pi=").append(nullToDash(paymentIntentId)).append(", evt=").append(stripeEventId);
        return sb.toString();
    }

    private boolean isRepurchase(String clerkUserId, String featureCode, String excludeSessionId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return false;
        }
        var q = new LambdaQueryWrapper<RechargeOrderEntity>()
                .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                .eq(RechargeOrderEntity::getFeatureCode, featureCode)
                .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED);
        if (excludeSessionId != null && !excludeSessionId.isEmpty()) {
            q.ne(RechargeOrderEntity::getStripeSessionId, excludeSessionId);
        }
        Long c = rechargeOrderMapper.selectCount(q);
        return c != null && c > 0;
    }

    private String lastTaskSummary(String clerkUserId, String featureCode) {
        if (FeatureCode.TASK_CREATE.getCode().equals(featureCode)) {
            TaskEntity t = taskMapper.selectOne(
                    new LambdaQueryWrapper<TaskEntity>()
                            .eq(TaskEntity::getClerkUserId, clerkUserId)
                            .eq(TaskEntity::getIsDeleted, 0)
                            .orderByDesc(TaskEntity::getCreatedAt)
                            .last("LIMIT 1"));
            if (t == null || t.getCreatedAt() == null) {
                return "暂无";
            }
            long days = ChronoUnit.DAYS.between(t.getCreatedAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE));
            return days + " 天前（按最近一条 Assignment 任务创建时间）";
        }
        if (FeatureCode.AI_DETECTION.getCode().equals(featureCode)) {
            HumanizerTaskEntity t = humanizerTaskMapper.selectOne(
                    new LambdaQueryWrapper<HumanizerTaskEntity>()
                            .eq(HumanizerTaskEntity::getClerkUserId, clerkUserId)
                            .eq(HumanizerTaskEntity::getTaskType, "DETECT")
                            .orderByDesc(HumanizerTaskEntity::getCreatedAt)
                            .last("LIMIT 1"));
            if (t == null || t.getCreatedAt() == null) {
                return "暂无";
            }
            long days = ChronoUnit.DAYS.between(t.getCreatedAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE));
            return days + " 天前（最近 Detection 任务）";
        }
        if (FeatureCode.HUMANIZER.getCode().equals(featureCode)) {
            HumanizerTaskEntity t = humanizerTaskMapper.selectOne(
                    new LambdaQueryWrapper<HumanizerTaskEntity>()
                            .eq(HumanizerTaskEntity::getClerkUserId, clerkUserId)
                            .eq(HumanizerTaskEntity::getTaskType, "HUMANIZE")
                            .orderByDesc(HumanizerTaskEntity::getCreatedAt)
                            .last("LIMIT 1"));
            if (t == null || t.getCreatedAt() == null) {
                return "暂无";
            }
            long days = ChronoUnit.DAYS.between(t.getCreatedAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE));
            return days + " 天前（最近 Humanize 任务）";
        }
        return "—";
    }

    private String repurchaseGapDays(String clerkUserId, String featureCode, String currentSessionId) {
        RechargeOrderEntity prev = rechargeOrderMapper.selectOne(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getFeatureCode, featureCode)
                        .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED)
                        .ne(RechargeOrderEntity::getStripeSessionId, currentSessionId)
                        .isNotNull(RechargeOrderEntity::getPaidAt)
                        .orderByDesc(RechargeOrderEntity::getPaidAt)
                        .last("LIMIT 1"));
        if (prev == null || prev.getPaidAt() == null) {
            return "—";
        }
        long days = ChronoUnit.DAYS.between(prev.getPaidAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE));
        return String.valueOf(days);
    }

    private String sumCompletedUsd(String featureCode, String clerkUserId) {
        List<RechargeOrderEntity> list = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getFeatureCode, featureCode)
                        .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED));
        long cents = list.stream().mapToLong(o -> o.getPriceCents() != null ? o.getPriceCents() : 0L).sum();
        return formatUsd(cents);
    }

    private String monthlyPaySummary(String clerkUserId) {
        UserProfileEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserProfileEntity>()
                        .eq(UserProfileEntity::getClerkUserId, clerkUserId)
                        .last("LIMIT 1"));
        if (user == null || user.getCreatedAt() == null) {
            return "用户注册时间未知";
        }
        long regDays = ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), LocalDate.now(DateTimeFormats.APP_ZONE)) + 1;
        if (regDays < 30) {
            return "不足一月（注册未满30天，各功能均显示不足一月）";
        }
        StringBuilder sb = new StringBuilder();
        for (String fc : List.of(FeatureCode.TASK_CREATE.getCode(), FeatureCode.AI_DETECTION.getCode(), FeatureCode.HUMANIZER.getCode())) {
            long cents = sumCentsForFeature(clerkUserId, fc);
            BigDecimal monthly = BigDecimal.valueOf(cents)
                    .divide(BigDecimal.valueOf(regDays), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(30))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            sb.append(featureDisplay(fc)).append(": $").append(monthly).append("/月; ");
        }
        return sb.toString();
    }

    private long sumCentsForFeature(String clerkUserId, String fc) {
        List<RechargeOrderEntity> list = rechargeOrderMapper.selectList(
                new LambdaQueryWrapper<RechargeOrderEntity>()
                        .eq(RechargeOrderEntity::getClerkUserId, clerkUserId)
                        .eq(RechargeOrderEntity::getFeatureCode, fc)
                        .eq(RechargeOrderEntity::getStatus, ORDER_COMPLETED));
        return list.stream().mapToLong(o -> o.getPriceCents() != null ? o.getPriceCents() : 0L).sum();
    }

    private String lastTaskLink(String clerkUserId, String featureCode) {
        String base = publicSiteUrl.replaceAll("/+$", "");
        if (FeatureCode.TASK_CREATE.getCode().equals(featureCode)) {
            TaskEntity t = taskMapper.selectOne(
                    new LambdaQueryWrapper<TaskEntity>()
                            .eq(TaskEntity::getClerkUserId, clerkUserId)
                            .eq(TaskEntity::getIsDeleted, 0)
                            .orderByDesc(TaskEntity::getCreatedAt)
                            .last("LIMIT 1"));
            if (t == null) {
                return "暂无任务记录";
            }
            return base + "/editor/" + TaskIdEncoder.encode(t.getId());
        }
        if (FeatureCode.AI_DETECTION.getCode().equals(featureCode) || FeatureCode.HUMANIZER.getCode().equals(featureCode)) {
            HumanizerTaskEntity t = humanizerTaskMapper.selectOne(
                    new LambdaQueryWrapper<HumanizerTaskEntity>()
                            .eq(HumanizerTaskEntity::getClerkUserId, clerkUserId)
                            .orderByDesc(HumanizerTaskEntity::getCreatedAt)
                            .last("LIMIT 1"));
            if (t == null) {
                return "暂无任务记录";
            }
            return humanizerPageNote(t.getId());
        }
        return "—";
    }

    private String humanizerPageNote(long humanizerTaskId) {
        return publicSiteUrl.replaceAll("/+$", "") + "/humanizer （任务ID: " + humanizerTaskId + "）";
    }

    private boolean hasPriorCompletedTask(String clerkUserId, long currentTaskId) {
        Long c = taskMapper.selectCount(
                new LambdaQueryWrapper<TaskEntity>()
                        .eq(TaskEntity::getClerkUserId, clerkUserId)
                        .eq(TaskEntity::getStatus, TASK_STATUS_COMPLETED)
                        .eq(TaskEntity::getIsDeleted, 0)
                        .lt(TaskEntity::getId, currentTaskId));
        return c != null && c > 0;
    }

    /** 同一 task_type 下是否存在更早的已完成 Humanizer 任务 */
    private boolean hasPriorHumanizerCompleted(String clerkUserId, long currentId, String taskType) {
        Long c = humanizerTaskMapper.selectCount(
                new LambdaQueryWrapper<HumanizerTaskEntity>()
                        .eq(HumanizerTaskEntity::getClerkUserId, clerkUserId)
                        .eq(HumanizerTaskEntity::getTaskType, taskType)
                        .eq(HumanizerTaskEntity::getStatus, "COMPLETED")
                        .lt(HumanizerTaskEntity::getId, currentId));
        return c != null && c > 0;
    }

    private String editorLink(long taskId) {
        return publicSiteUrl.replaceAll("/+$", "") + "/editor/" + TaskIdEncoder.encode(taskId);
    }

    private String formatTaskFileDistribution(Long taskId) {
        List<TaskFileEntity> links = taskFileMapper.selectList(
                new LambdaQueryWrapper<TaskFileEntity>()
                        .eq(TaskFileEntity::getTaskId, taskId)
                        .orderByAsc(TaskFileEntity::getFileOrder));
        if (links.isEmpty()) {
            return "无";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TaskFileEntity tf : links) {
            FileEntity fe = fileMapper.selectById(tf.getFileId());
            if (fe == null) {
                continue;
            }
            String ext = fe.getFileExtension();
            if (ext != null && ext.startsWith(".")) {
                ext = ext.substring(1);
            }
            if (ext == null || ext.isEmpty()) {
                ext = "file";
            }
            counts.merge(ext.toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "无";
        }
        return counts.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(" / "));
    }

    private String extractClarifyingSnippet(String requirementJson) {
        if (requirementJson == null || requirementJson.isBlank()) {
            return "无";
        }
        try {
            JsonElement root = JsonParser.parseString(requirementJson);
            if (!root.isJsonObject()) {
                return truncatePlain(requirementJson, 400);
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("clarifyingQuestions")) {
                return "无";
            }
            String raw = obj.get("clarifyingQuestions").toString();
            if (raw.length() > 800) {
                return raw.substring(0, 800) + "…";
            }
            return raw;
        } catch (Exception e) {
            return truncatePlain(requirementJson, 400);
        }
    }

    private String truncateWords(String text, int maxWords) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length <= maxWords) {
            return text;
        }
        return String.join(" ", java.util.Arrays.copyOf(parts, maxWords)) + " …";
    }

    private static String truncatePlain(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String formatSecondsAsMmSs(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return "—";
        }
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d分%02d秒", m, s);
    }

    private static String mapHumanizerSource(String source) {
        if (source == null) {
            return "—";
        }
        return switch (source) {
            case "EDITOR" -> "编辑器内";
            case "HUMANIZER_PAGE" -> "编辑器外 / 独立页";
            default -> source;
        };
    }

    private static String feedbackTimingCn(String triggerCode) {
        if (triggerCode == null) {
            return "—";
        }
        return switch (triggerCode) {
            case "task_download_first" -> "点击 Download";
            case "editor_back_first" -> "从编辑页返回任务详情页";
            case "editor_copy_first" -> "编辑页复制或下滑到底部";
            case "detection_complete_first" -> "Detection 流程结束（首评）";
            case "humanizer_complete_first" -> "Humanizer 流程结束（首评）";
            default -> triggerCode;
        };
    }

    private static String blankToUserSkipped(String s) {
        if (s == null || s.isBlank()) {
            return "用户未填写";
        }
        return s;
    }

    private static String nullToDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    private void appendVerlaConversationFeedback(StringBuilder sb, String clerkUserId, long conversationId) {
        VerlaConversationEntity conversation = verlaConversationMapper.selectById(conversationId);
        if (conversation == null) {
            sb.append("- **V2 Conversation**: 未找到 conversation id=").append(conversationId).append("\n");
            return;
        }
        boolean oldUser = hasPriorVerlaConversation(clerkUserId, conversationId);
        sb.append("- **用户类型**: ").append(oldUser ? "老用户" : "新用户").append("\n");
        sb.append("- **Conversation ID**: ").append(conversationId).append("\n");
        sb.append("- **标题**: ").append(nullToDash(conversation.getTitle())).append("\n");
        sb.append("- **主意图**: ").append(nullToDash(conversation.getPrimaryIntent())).append("\n");
        sb.append("- **状态**: ").append(nullToDash(conversation.getStatus())).append("\n");
        sb.append("- **任务链接**: ").append(assignmentConversationLink(conversationId)).append("\n");
    }

    private boolean hasPriorVerlaConversation(String clerkUserId, long currentConversationId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        Long count = verlaConversationMapper.selectCount(
                new LambdaQueryWrapper<VerlaConversationEntity>()
                        .eq(VerlaConversationEntity::getUserId, clerkUserId)
                        .ne(VerlaConversationEntity::getStatus, "deleted")
                        .lt(VerlaConversationEntity::getId, currentConversationId));
        return count != null && count > 0;
    }

    private String assignmentConversationLink(long conversationId) {
        return publicSiteUrl.replaceAll("/+$", "") + "/assignments/" + conversationId;
    }

    private static Long parseVerlaConversationSubjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith(PREFIX_VERLA_CONVERSATION_SUBJECT)) {
            value = value.substring(PREFIX_VERLA_CONVERSATION_SUBJECT.length());
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 反馈会话 subject_id：历史为 DB 数字串；前端对外任务 id 为 Sqids 短码（与 {@link TaskIdEncoder} 一致）。
     */
    private static Long parseFeedbackSubjectId(String raw) {
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

    private static String featureDisplay(String featureCode) {
        if (featureCode == null) {
            return "—";
        }
        return switch (featureCode) {
            case "task_create" -> "Assignment";
            case "ai_detection" -> "AI Detection";
            case "humanizer" -> "Humanizer";
            default -> featureCode;
        };
    }

    private static String formatPackageLine(long quotaAmount, int priceCents, String currency, String packageType, String featureCode) {
        String money = formatMoney(priceCents, currency);
        String unit = (FeatureCode.AI_DETECTION.getCode().equals(featureCode) || FeatureCode.HUMANIZER.getCode().equals(featureCode))
                ? "额度单位(字/配额)"
                : "次";
        return quotaAmount + " " + unit + " · " + money + (packageType != null ? " · pkg:" + packageType : "");
    }

    private static String formatMoney(int priceCents, String currency) {
        String cur = currency != null ? currency.toUpperCase(Locale.ROOT) : "USD";
        BigDecimal amt = BigDecimal.valueOf(priceCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return amt + " " + cur;
    }

    private static String formatUsd(long cents) {
        BigDecimal amt = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return "$" + amt;
    }

    private static String appNowLabel() {
        return DateTimeFormats.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (UTC+8)";
    }

    private static Map<String, Object> baseMeta(String kind, String eventId, String clerkUserId, String featureCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("stripe_event_id", eventId);
        m.put("clerk_user_id", clerkUserId);
        m.put("feature_code", featureCode);
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}
