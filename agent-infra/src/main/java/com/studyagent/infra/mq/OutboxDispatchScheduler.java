package com.studyagent.infra.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
     * <p>
     * - 老链路（taskId 非空 / 无 sessionId）：保持原有 envelope = { eventId, action, taskId, payload, timestamp }
     *   走 {@link RabbitMQConfig#COMMAND_EXCHANGE} + routingKey = action。
     * - Verla 链路（{@link MqOutbox#isVerla()} = true）：直接把 mq_outbox.payload 当 JSON body 透传，
     *   走自带的 exchange / routing_key，并把 messageId / correlationId / orderingKey 放进 message header，
     *   便于消费侧（Py / Java listener）按 header 路由与去重，不破坏信封 schema。
     */
    public void sendMessage(MqOutbox message) {
        try {
            String exchange;
            String routingKey;
            Message amqpMessage;

            if (message.isVerla()) {
                exchange = nullToDefault(message.getExchange(), RabbitMQConfig.COMMAND_EXCHANGE);
                routingKey = message.getRoutingKey();
                if (routingKey == null || routingKey.isEmpty()) {
                    handleSendFailure(message, "Verla outbox missing routingKey");
                    return;
                }
                amqpMessage = buildVerlaMessage(message);
            } else {
                exchange = RabbitMQConfig.COMMAND_EXCHANGE;
                routingKey = message.getAction();
                amqpMessage = buildLegacyMessage(message);
            }

            CorrelationData correlationData = new CorrelationData(message.getId().toString());
            rabbitTemplate.send(exchange, routingKey, amqpMessage, correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture().get(3, TimeUnit.SECONDS);

            if (confirm.isAck()) {
                mqOutboxRepository.markAsSent(message.getId());
                log.info("Broker ack: eventId={}, action={}, exchange={}, rk={}",
                        message.getEventId(), message.getAction(), exchange, routingKey);
            } else {
                handleSendFailure(message, "Broker NACK: " + confirm.getReason());
            }

        } catch (Exception e) {
            log.error("发送 MQ 消息发生异常: eventId={}", message.getEventId(), e);
            handleSendFailure(message, e.getMessage());
        }
    }

    private Message buildLegacyMessage(MqOutbox message) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", message.getEventId());
        body.put("action", message.getAction());
        body.put("taskId", message.getTaskId());
        body.put("timestamp", LocalDateTime.now().toString());

        if (message.getPayload() != null && !message.getPayload().isEmpty()) {
            try {
                body.put("payload", objectMapper.readTree(message.getPayload()));
            } catch (Exception e) {
                body.put("payload", message.getPayload());
            }
        } else {
            body.put("payload", new HashMap<>());
        }

        MessageConverter converter = rabbitTemplate.getMessageConverter();
        MessageProperties props = new MessageProperties();
        props.setMessageId(message.getEventId());
        return converter.toMessage(body, props);
    }

    private Message buildVerlaMessage(MqOutbox message) {
        String payload = message.getPayload() == null ? "{}" : message.getPayload();
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(message.getEventId())
                .setHeader("messageId", message.getEventId())
                .setHeader("correlationId", message.getCorrelationId())
                .setHeader("orderingKey", message.getOrderingKey())
                .setHeader("schemaVersion", message.getSchemaVersion())
                .setHeader("conversationId", message.getConversationId())
                .setHeader("turnId", message.getTurnId())
                .setHeader("sessionId", message.getSessionId())
                .setHeader("action", message.getAction())
                .build();
    }

    private static String nullToDefault(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
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
