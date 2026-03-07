package com.studyagent.infra.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 事务发件箱定时调度器
 * 负责扫描数据库中未发送的消息（包括初始发送失败的消息），并重试投递至 RabbitMQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatchScheduler {

    private final MqOutboxRepository mqOutboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 每 5 秒执行一次扫描
     * 生产环境可以考虑搭配分布式锁避免多实例同时拉取相同记录（假设目前为单实例）
     */
    @Scheduled(fixedDelayString = "${mq.outbox.scan-interval:5000}")
    public void dispatchPendingMessages() {
        LocalDateTime now = LocalDateTime.now();
        List<MqOutbox> pendingMessages = mqOutboxRepository.findPendingMessages(100, now);

        if (pendingMessages.isEmpty()) {
            return;
        }

        log.info("发现 {} 条待发送或待重试的 MQ 消息", pendingMessages.size());

        for (MqOutbox message : pendingMessages) {
            sendMessage(message);
        }
    }

    /**
     * 发送单条消息到 RabbitMQ
     */
    public void sendMessage(MqOutbox message) {
        try {
            // 构建消息体
            Map<String, Object> body = new HashMap<>();
            body.put("eventId", message.getEventId());
            body.put("action", message.getAction());
            body.put("taskId", message.getTaskId());
            body.put("timestamp", LocalDateTime.now().toString());

            if (message.getPayload() != null && !message.getPayload().isEmpty()) {
                try {
                    body.put("payload", objectMapper.readTree(message.getPayload()));
                } catch (Exception e) {
                    body.put("payload", message.getPayload()); // 如果不是JSON对象则原样发送字符串
                }
            } else {
                body.put("payload", new HashMap<>());
            }

            String routingKey = message.getAction();

            // 使用 rabbitTemplate 的确认机制回调
            CorrelationData correlationData = new CorrelationData(message.getId().toString());

            // 发送消息
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.COMMAND_EXCHANGE,
                    routingKey,
                    body,
                    correlationData);

            // 同步等待 Confirm（可选：也可以做成异步Callback机制以提升吞吐量。为了稳妥此处可暂用简单机制，由 ConfirmCallback
            // 异步处理或直接使用 waitForConfirms）
            // 在这里我们利用 getFuture() 阻塞等待最多 3 秒获取 ACK
            CorrelationData.Confirm confirm = correlationData.getFuture().get(3, TimeUnit.SECONDS);

            if (confirm.isAck()) {
                // Broker 成功接收
                mqOutboxRepository.markAsSent(message.getId());
                log.info("Broker 成功接收消息: eventId={}, action={}", message.getEventId(), message.getAction());
            } else {
                // Broker 明确拒收
                handleSendFailure(message, "Broker NACK: " + confirm.getReason());
            }

        } catch (Exception e) {
            log.error("发送 MQ 消息发生异常: eventId={}", message.getEventId(), e);
            handleSendFailure(message, e.getMessage());
        }
    }

    private void handleSendFailure(MqOutbox message, String errorMsg) {
        int currentRetryCount = message.getRetryCount();
        log.warn("消息发送失败, ID={}, Retry={}, Error={}", message.getId(), currentRetryCount, errorMsg);

        if (currentRetryCount + 1 >= message.getMaxRetries()) {
            log.error("消息重试次数已达上限, 标记为最终失败: ID={}, Action={}", message.getId(), message.getAction());
            mqOutboxRepository.markAsFailed(message.getId(), errorMsg);
            // TODO: 此处可接入告警系统 (如邮件/企业微信/钉钉等)
        } else {
            // 计算指数退避时间: 第1次重试等10秒，第2次等30秒，第三次等90秒...
            // 简单计算：10 * (3 ^ retryCount) 秒
            int delaySeconds = 10 * (int) Math.pow(3, currentRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);

            mqOutboxRepository.markForRetry(message.getId(), errorMsg, nextRetryAt);
        }
    }
}
