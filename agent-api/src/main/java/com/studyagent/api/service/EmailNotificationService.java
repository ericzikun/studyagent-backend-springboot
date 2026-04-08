package com.studyagent.api.service;

import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.service.domain.user.ClerkClient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 邮件通知服务
 *
 * 使用 Resend REST API 发送任务完成邮件。
 * 全程 best-effort：任何异常只记日志，不影响主流程。
 */
@Service
@Slf4j
public class EmailNotificationService {

    private static final DateTimeFormatter COMPLETED_AT_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH);
    private static final String DEFAULT_SUBJECT_TASK_TITLE = "Your Research";
    private static final String BRAND_URL = "https://verla.io";
    private static final int SUBJECT_TASK_TITLE_MAX_LENGTH = 100;
    private static final String EMAIL_LOGO_CONTENT_ID = "verla-logo";
    private static final String EMAIL_LOGO_RESOURCE_PATH = "email-assets/notification-icon.png.base64";

    private final ClerkClient clerkClient;
    private final WebClient webClient;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${notification.email.resend-api-key:}")
    private String resendApiKey;

    @Value("${notification.email.from-email:Verla <notifications@send.verla.io>}")
    private String fromEmail;

    @Value("${notification.email.frontend-url:https://verla.io}")
    private String frontendUrl;

    public EmailNotificationService(ClerkClient clerkClient, WebClient webClient) {
        this.clerkClient = clerkClient;
        this.webClient = webClient;
    }

    /**
     * 任务完成后发送邮件通知（best-effort，异常只 log 不抛）
     */
    public void sendTaskCompletedEmail(TaskEntity task) {
        try {
            if (!emailEnabled) {
                log.debug("邮件通知未启用，跳过: taskId={}", task.getId());
                return;
            }
            if (resendApiKey == null || resendApiKey.isBlank()) {
                log.warn("RESEND_API_KEY 未配置，跳过邮件发送: taskId={}", task.getId());
                return;
            }
            if (task.getClerkUserId() == null || task.getClerkUserId().isBlank()) {
                log.warn("任务无关联用户，跳过邮件发送: taskId={}", task.getId());
                return;
            }

            String userEmail = clerkClient.getUserEmail(task.getClerkUserId());
            if (userEmail == null || userEmail.isBlank()) {
                log.warn("无法获取用户邮箱，跳过邮件发送: taskId={}, clerkUserId={}",
                        task.getId(), task.getClerkUserId());
                return;
            }

            String taskTitle = task.getTaskTitle() != null ? task.getTaskTitle() : DEFAULT_SUBJECT_TASK_TITLE;
            String subject = buildSubject(taskTitle);
            String completedAt = formatCompletedAt(task.getFinishTime());
            String viewUrl = frontendUrl + "/workflow?taskId=" + task.getId();
            String html = buildEmailHtml(taskTitle, completedAt, viewUrl);
            String text = buildPlainText(taskTitle, completedAt, viewUrl);

            sendViaResend(userEmail, subject, html, text, task.getId());

        } catch (Exception e) {
            log.warn("发送任务完成邮件异常: taskId={}, error={}", task.getId(), e.getMessage());
        }
    }

    private void sendViaResend(String to, String subject, String html, String text, Long taskId) {
        Map<String, Object> body = new HashMap<>();
        body.put("from", fromEmail);
        body.put("to", List.of(to));
        body.put("subject", subject);
        body.put("html", html);
        body.put("text", text);
        body.put("attachments", List.of(buildInlineLogoAttachment()));

        webClient.post()
                .uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + resendApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .subscribe(
                        response -> log.info("邮件发送成功: taskId={}, to={}, resendId={}",
                                taskId, to, response.get("id")),
                        error -> log.warn("邮件发送失败: taskId={}, to={}, error={}",
                                taskId, to, error.getMessage())
                );
    }

    private Map<String, Object> buildInlineLogoAttachment() {
        return Map.of(
                "filename", "notification-icon.png",
                "content", loadInlineLogoBase64(),
                "content_type", "image/png",
                "content_id", EMAIL_LOGO_CONTENT_ID
        );
    }

    private String loadInlineLogoBase64() {
        try (var inputStream = new ClassPathResource(EMAIL_LOGO_RESOURCE_PATH).getInputStream()) {
            return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", "");
        } catch (IOException e) {
            throw new IllegalStateException("无法加载邮件 logo 资源: " + EMAIL_LOGO_RESOURCE_PATH, e);
        }
    }

    private String buildPlainText(String taskTitle, String completedAt, String viewUrl) {
        return """
                Task complete

                Your report is ready to review.

                Task: %s
                Completed at: %s

                View results: %s

                —
                Verla · https://verla.io
                """.formatted(taskTitle, completedAt, viewUrl);
    }

    private String buildEmailHtml(String taskTitle, String completedAt, String viewUrl) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Task complete</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f1f5f9;padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:540px;background-color:#ffffff;border-radius:12px;border:1px solid #e2e8f0;overflow:hidden;">
                          <tr>
                            <td style="padding:20px 24px 16px;text-align:left;border-bottom:1px solid #e2e8f0;">
                              <table role="presentation" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="vertical-align:middle;padding-right:10px;">
                                    <a href="%s" target="_blank" rel="noopener noreferrer" style="display:block;text-decoration:none;">
                                      <img src="cid:%s" alt="Verla" width="32" height="32" style="display:block;border:0;">
                                    </a>
                                  </td>
                                  <td style="vertical-align:middle;">
                                    <a href="%s" target="_blank" rel="noopener noreferrer" style="font-size:32px;font-weight:700;line-height:32px;color:#6366f1;letter-spacing:-0.02em;text-decoration:none;">Verla</a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:22px 24px 26px;text-align:left;">
                              <h1 style="margin:0 0 8px;font-size:19px;font-weight:700;color:#1e293b;line-height:1.3;">Task complete</h1>
                              <p style="margin:0 0 18px;font-size:15px;color:#64748b;line-height:1.5;">
                                Your report is ready to review.
                              </p>
                              <p style="margin:0 0 6px;font-size:14px;color:#334155;line-height:1.45;">
                                <span style="font-weight:600;color:#6366f1;">Task:</span> %s
                              </p>
                              <p style="margin:0 0 22px;font-size:14px;color:#334155;line-height:1.45;">
                                <span style="font-weight:600;color:#6366f1;">Completed at:</span> %s
                              </p>
                              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td align="center" style="padding:0;">
                                    <table role="presentation" cellpadding="0" cellspacing="0">
                                      <tr>
                                        <td style="background-color:#6366f1;border-radius:8px;">
                                          <a href="%s" target="_blank" rel="noopener noreferrer" style="display:inline-block;padding:11px 22px;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;">
                                            View results
                                          </a>
                                        </td>
                                      </tr>
                                    </table>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:14px 24px 18px;background-color:#f8fafc;border-top:1px solid #e2e8f0;text-align:left;">
                              <p style="margin:0;font-size:11px;color:#94a3b8;line-height:1.55;">
                                Automated message from <a href="%s" style="color:#6366f1;text-decoration:none;">Verla</a>.
                                You received this because a task you submitted finished.
                              </p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(BRAND_URL, EMAIL_LOGO_CONTENT_ID, BRAND_URL, escapeHtml(taskTitle), escapeHtml(completedAt), viewUrl, BRAND_URL);
    }

    static String buildSubject(String taskTitle) {
        String normalizedTitle = normalizeSubjectTitle(taskTitle);
        if (normalizedTitle.length() > SUBJECT_TASK_TITLE_MAX_LENGTH) {
            normalizedTitle = normalizedTitle.substring(0, SUBJECT_TASK_TITLE_MAX_LENGTH - 3) + "...";
        }
        return "Task complete — " + normalizedTitle;
    }

    private static String normalizeSubjectTitle(String taskTitle) {
        String fallback = DEFAULT_SUBJECT_TASK_TITLE;
        if (taskTitle == null || taskTitle.isBlank()) {
            return fallback;
        }
        String normalized = taskTitle
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return Objects.equals(normalized, "") ? fallback : normalized;
    }

    private static String formatCompletedAt(LocalDateTime finishTime) {
        LocalDateTime t = finishTime != null ? finishTime : LocalDateTime.now();
        return t.format(COMPLETED_AT_FORMAT);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
