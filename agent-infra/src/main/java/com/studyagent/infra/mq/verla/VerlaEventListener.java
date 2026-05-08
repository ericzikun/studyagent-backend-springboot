package com.studyagent.infra.mq.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaInboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Verla 事件入站监听器（PR-12）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §8.2。
 * <ul>
 *     <li>每个 shard 队列单 listener，依赖 {@code x-single-active-consumer} 严格串行。</li>
 *     <li>解析 envelope → 调 {@link VerlaInboxService#ingest(VerlaEventEnvelope)}。</li>
 *     <li>整个 ingest（含 handler dispatch）成功 → ack；任何异常 → nack-no-requeue 进 DLX。</li>
 * </ul>
 * <p>
 * 注意：4 个 shard 队列名硬编码以避免 SpEL 解析 List 失败；
 * 若以后 shardCount 变成可调，将这里替换成基于 {@code @Value} 的 SpEL 数组注入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaEventListener {

    private final ObjectMapper objectMapper;
    private final VerlaInboxService inboxService;

    @RabbitListener(
            queues = {
                    "verla.event.s00",
                    "verla.event.s01",
                    "verla.event.s02",
                    "verla.event.s03"
            },
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onMessage(Message msg, Channel channel) throws IOException {
        long deliveryTag = msg.getMessageProperties().getDeliveryTag();
        String routingKey = msg.getMessageProperties().getReceivedRoutingKey();
        String mqMessageId = msg.getMessageProperties().getMessageId();
        VerlaEventEnvelope env = null;
        try {
            env = parse(msg);
            inboxService.ingest(env);
            channel.basicAck(deliveryTag, false);
            log.debug("[Verla/listener] acked rk={} messageId={} sessionId={} seq={}",
                    routingKey, env.getMessageId(),
                    env.getSession() == null ? null : env.getSession().getSessionId(),
                    env.getEventSeq());
        } catch (Exception e) {
            log.error("[Verla/listener] handle failed rk={} mqMsgId={} envelopeMsgId={} → nack to DLX",
                    routingKey, mqMessageId,
                    env == null ? null : env.getMessageId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private VerlaEventEnvelope parse(Message msg) throws IOException {
        byte[] body = msg.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("empty event body");
        }
        // Spring AMQP 默认 Jackson2JsonMessageConverter 没注册 JavaTimeModule,
        // 这里用全局 ObjectMapper（spring-boot 自动配置已注册 JavaTimeModule）反序列化。
        return objectMapper.readValue(new String(body, StandardCharsets.UTF_8), VerlaEventEnvelope.class);
    }
}
