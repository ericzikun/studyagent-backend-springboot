package com.studyagent.infra.mq.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.infra.mq.VerlaRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Mock Py command consumer（dev / local profile）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §18 PR-11。
 * <p>
 * 目的：
 * <ol>
 *   <li>验证 Java→Py 的全链路：mq_outbox → OutboxDispatchScheduler →
 *       studyagent.command exchange → verla.cmd.* 队列。</li>
 *   <li>解耦 Day 2 的 Java 端开发，不必等真 Py 服务上线。</li>
 *   <li>后续 Day 3 listener 接通后，增强为回写事件以走通双向闭环。</li>
 * </ol>
 *
 * 注意：仅在 {@code spring.profiles.active in (dev, local)} 时启用，
 * 生产环境一定不能启动此 bean，避免误吞真 Py 应该消费的消息。
 */
@Slf4j
@Component
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class MockPyCommandConsumer {

    private final ObjectMapper objectMapper;

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_PLAN_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onPlan(Message msg, Channel channel) throws IOException {
        handle("PLAN", msg, channel);
    }

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_AGENT_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onAgent(Message msg, Channel channel) throws IOException {
        handle("AGENT", msg, channel);
    }

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_CONTROL_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onControl(Message msg, Channel channel) throws IOException {
        handle("CONTROL", msg, channel);
    }

    // ============================================================

    private void handle(String tag, Message msg, Channel channel) throws IOException {
        MessageProperties props = msg.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String routingKey = props.getReceivedRoutingKey();
        String correlationId = stringHeader(props, "correlationId");
        String orderingKey = stringHeader(props, "orderingKey");
        String action = stringHeader(props, "action");
        String messageId = stringHeader(props, "messageId");

        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            VerlaCommandEnvelope env = parseEnvelope(body);

            log.info("[MockPy/{}] consumed: rk={} action={} messageId={} correlationId={} ordering={} session={} turn={} conv={}",
                    tag,
                    routingKey,
                    action,
                    messageId,
                    correlationId,
                    orderingKey,
                    refSessionId(env),
                    refTurnId(env),
                    refConvId(env));

            // MVP 阶段：仅 ack，不回写事件（事件链路在 Day 3 PR-12 之后接通）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[MockPy/{}] handle failed, requeue=false, will route to DLX. action={}, messageId={}",
                    tag, action, messageId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private VerlaCommandEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, VerlaCommandEnvelope.class);
        } catch (Exception e) {
            log.warn("[MockPy] envelope parse failed, fallback to raw map. body.size={}", body.length());
            return null;
        }
    }

    private static String stringHeader(MessageProperties props, String name) {
        Object v = props.getHeaders().get(name);
        return v == null ? null : v.toString();
    }

    private static Long refSessionId(VerlaCommandEnvelope env) {
        return env == null || env.getSession() == null ? null : env.getSession().getSessionId();
    }

    private static Long refTurnId(VerlaCommandEnvelope env) {
        return env == null || env.getTurn() == null ? null : env.getTurn().getTurnId();
    }

    private static Long refConvId(VerlaCommandEnvelope env) {
        return env == null || env.getConversation() == null ? null : env.getConversation().getConversationId();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> readMap(byte[] body) throws IOException {
        return objectMapper.readValue(body, Map.class);
    }
}
