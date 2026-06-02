package com.studyagent.infra.mq.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.util.VerlaRoutingKey;
import com.studyagent.common.verla.util.VerlaShardCalculator;
import com.studyagent.infra.mq.VerlaRabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes Java-side MockPy events into the Verla event exchange.
 *
 * This class owns event envelope construction, routing key selection, and
 * per-session eventSeq assignment. It does not decide which mock business
 * events to emit; {@link MockPyCommandConsumer} remains responsible for command
 * routing and mock scenario scheduling.
 */
@Slf4j
class MockPyEventPublisher {

    private static final String PRODUCER_SERVICE = "py-mock";

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final VerlaRabbitConfig rabbitConfig;
    private final String instanceId;
    /** Maintain monotonic eventSeq per session, matching the real Python contract. */
    private final ConcurrentHashMap<Long, AtomicLong> sessionSeq = new ConcurrentHashMap<>();

    MockPyEventPublisher(ObjectMapper objectMapper,
                         RabbitTemplate rabbitTemplate,
                         VerlaRabbitConfig rabbitConfig,
                         String instanceId) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitConfig = rabbitConfig;
        this.instanceId = instanceId;
    }

    void publishEvent(VerlaCommandEnvelope cmd, VerlaAgentEventType type,
                      Map<String, Object> payload) {
        Long sessionId = cmd.getSession().getSessionId();
        long seq = sessionSeq.computeIfAbsent(sessionId, k -> new AtomicLong(0L)).incrementAndGet();
        int shard = VerlaShardCalculator.shardOf(sessionId, rabbitConfig.getShardCount());
        String routingKey = VerlaRoutingKey.forEvent(type, shard);
        String messageId = "evt-mock-" + UUID.randomUUID();

        VerlaEventEnvelope env = VerlaEventEnvelope.builder()
                .schemaVersion(1)
                .messageId(messageId)
                .eventId(messageId)
                .correlationId(cmd.getCorrelationId())
                .orderingKey(cmd.getOrderingKey())
                .eventType(type.name())
                .routingKey(routingKey)
                .eventSeq(seq)
                .timestamp(Instant.now())
                .producer(VerlaProducerInfo.builder()
                        .service(PRODUCER_SERVICE)
                        .instanceId(instanceId)
                        .build())
                .conversation(cmd.getConversation())
                .turn(cmd.getTurn())
                .session(cmd.getSession())
                .payload(payload)
                .build();

        try {
            byte[] body = objectMapper.writeValueAsBytes(env);
            Message m = MessageBuilder.withBody(body)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setMessageId(messageId)
                    .setHeader("messageId", messageId)
                    .setHeader("correlationId", env.getCorrelationId())
                    .setHeader("orderingKey", env.getOrderingKey())
                    .setHeader("eventType", type.name())
                    .setHeader("eventSeq", seq)
                    .setHeader("schemaVersion", 1)
                    .setHeader("conversationId", refConvId(env))
                    .setHeader("turnId", refTurnId(env))
                    .setHeader("sessionId", sessionId)
                    .build();
            rabbitTemplate.send(VerlaRabbitConfig.EVENTS_EXCHANGE, routingKey, m);
            log.info("[MockPy/event] published rk={} seq={} type={} sessionId={}",
                    routingKey, seq, type, sessionId);
        } catch (Exception e) {
            log.error("[MockPy/event] publish failed sessionId={} type={} seq={}",
                    sessionId, type, seq, e);
        }
    }

    private static Long refConvId(VerlaEventEnvelope env) {
        return env == null || env.getConversation() == null ? null : env.getConversation().getConversationId();
    }

    private static Long refTurnId(VerlaEventEnvelope env) {
        return env == null || env.getTurn() == null ? null : env.getTurn().getTurnId();
    }
}
