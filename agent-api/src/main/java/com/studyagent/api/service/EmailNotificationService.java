package com.studyagent.api.service;

import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.service.domain.user.ClerkClient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件通知服务
 *
 * 使用 Resend REST API 发送任务完成邮件。
 * 全程 best-effort：任何异常只记日志，不影响主流程。
 */
@Service
@Slf4j
public class EmailNotificationService {

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

            String taskTitle = task.getTaskTitle() != null ? task.getTaskTitle() : "Your Research";
            String subject = "Your research is ready — " + taskTitle;
            String viewUrl = frontendUrl + "/workflow?taskId=" + task.getId();
            String html = buildEmailHtml(taskTitle, viewUrl);
            String text = buildPlainText(taskTitle, viewUrl);

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

    private String buildPlainText(String taskTitle, String viewUrl) {
        return """
                Hi there,

                Your research task "%s" has been completed and is ready for review.

                View your results: %s

                Best,
                The Verla Team

                ---
                This is an automated notification from Verla (https://verla.io).
                You received this email because a task you submitted has been completed.
                """.formatted(taskTitle, viewUrl);
    }

    private String buildEmailHtml(String taskTitle, String viewUrl) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Task Completed</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f4f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f5;padding:40px 20px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:12px;overflow:hidden;">
                          <tr>
                            <td style="padding:32px 40px;text-align:center;">
                              <img src="%s/notification-icon.svg" alt="Verla" width="40" height="40" style="display:inline-block;vertical-align:middle;margin-right:10px;">
                              <span style="display:inline-block;vertical-align:middle;color:#18181b;font-size:20px;font-weight:600;letter-spacing:-0.02em;">Verla</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 40px 40px;">
                              <p style="margin:0 0 8px;font-size:14px;color:#71717a;">Task Completed</p>
                              <h2 style="margin:0 0 16px;font-size:20px;color:#18181b;font-weight:600;line-height:1.4;">%s</h2>
                              <p style="margin:0 0 28px;font-size:15px;color:#3f3f46;line-height:1.6;">
                                Your research paper has been completed and is ready for review. Click the button below to view the results.
                              </p>
                              <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto;">
                                <tr>
                                  <td style="background-color:#18181b;border-radius:8px;">
                                    <a href="%s" target="_blank" style="display:inline-block;padding:12px 32px;color:#ffffff;font-size:15px;font-weight:500;text-decoration:none;">
                                      View Results
                                    </a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 40px;border-top:1px solid #e4e4e7;">
                              <p style="margin:0;font-size:12px;color:#a1a1aa;line-height:1.5;text-align:center;">
                                This is an automated notification from <a href="https://verla.io" style="color:#a1a1aa;">Verla</a>.
                                You received this email because a task you submitted has been completed.
                              </p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(frontendUrl, escapeHtml(taskTitle), viewUrl);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
