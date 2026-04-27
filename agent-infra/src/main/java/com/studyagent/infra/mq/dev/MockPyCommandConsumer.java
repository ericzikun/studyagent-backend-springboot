package com.studyagent.infra.mq.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.util.VerlaRoutingKey;
import com.studyagent.common.verla.util.VerlaShardCalculator;
import com.studyagent.infra.mq.VerlaRabbitConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock Py command consumer（dev / local profile）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §18 PR-11、docs/verla-端到端调用链路-做作业示例.md §3。
 * <p>
 * 职责：
 * <ol>
 *   <li>验证 Java→Py 全链路：mq_outbox → OutboxDispatchScheduler →
 *       studyagent.command exchange → verla.cmd.* 队列。</li>
 *   <li>解耦 Py 服务联调：收到命令后异步把事件回推到 {@link VerlaRabbitConfig#EVENTS_EXCHANGE}，
 *       让 Java 侧 inbox/cursor/handler 跑完一整个 happy path。</li>
 * </ol>
 * <p>
 * 模拟时序（与文档 §6 / §11.5 一致）：
 * <pre>
 * cmd.plan.intent      ──► (200ms)  PLAN_INTENT_RESOLVED { intent, slots }
 * cmd.agent.run        ──► (50ms)   AGENT_STARTED
 *                         (200ms)  AGENT_STEP_STREAM_CHUNK { delta: "片段 1 ..." }
 *                         (400ms)  AGENT_STEP_STREAM_CHUNK { delta: "片段 2 ..." }
 *                         (600ms)  AGENT_STEP_STREAM_CHUNK { delta: "片段 3 ..." }
 *                         (900ms)  AGENT_COMPLETED { summary }
 * cmd.agent.control.cancel ─► (100ms) AGENT_CANCELLED
 * cmd.agent.control.retry  ─► no-op (留给真 Py 处理)
 * </pre>
 * <p>
 * 仅在 {@code spring.profiles.active in (dev, local)} 时启用，生产环境一定不能启动此 bean。
 */
@Slf4j
@Component
@Profile({"dev", "local"})
public class MockPyCommandConsumer {

    private static final String PRODUCER_SERVICE = "py-mock";

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final VerlaRabbitConfig rabbitConfig;
    private final ScheduledExecutorService scheduler;
    /** 按 sessionId 维护事件 seq，保证同 session 内单调递增 */
    private final ConcurrentHashMap<Long, AtomicLong> sessionSeq = new ConcurrentHashMap<>();
    private final String instanceId;

    public MockPyCommandConsumer(ObjectMapper objectMapper,
                                 RabbitTemplate rabbitTemplate,
                                 VerlaRabbitConfig rabbitConfig,
                                 @Value("${verla.mq.mock.delay-base-ms:50}") long delayBaseMs) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitConfig = rabbitConfig;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "verla-mock-event-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.instanceId = "local-" + Long.toHexString(System.nanoTime() & 0xffffffL);
        log.info("[MockPy] enabled, instanceId={}, delayBaseMs={}", instanceId, delayBaseMs);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ============================================================
    // RabbitListener 入口
    // ============================================================

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_PLAN_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onPlan(Message msg, Channel channel) throws IOException {
        handle("PLAN", msg, channel, this::schedulePlanResponse);
    }

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_AGENT_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onAgent(Message msg, Channel channel) throws IOException {
        handle("AGENT", msg, channel, this::scheduleAgentResponse);
    }

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_CONTROL_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onControl(Message msg, Channel channel) throws IOException {
        handle("CONTROL", msg, channel, this::scheduleControlResponse);
    }

    // ============================================================
    // 公共入口
    // ============================================================

    private void handle(String tag, Message msg, Channel channel,
                        java.util.function.Consumer<VerlaCommandEnvelope> responder) throws IOException {
        MessageProperties props = msg.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String routingKey = props.getReceivedRoutingKey();
        String messageId = stringHeader(props, "messageId");

        VerlaCommandEnvelope env = null;
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            env = parseEnvelope(body);

            log.info("[MockPy/{}] consumed: rk={} action={} messageId={} session={} turn={} conv={}",
                    tag, routingKey,
                    env == null ? null : env.getAction(),
                    messageId,
                    refSessionId(env), refTurnId(env), refConvId(env));

            channel.basicAck(deliveryTag, false);

            if (env != null && env.getSession() != null && env.getSession().getSessionId() != null) {
                responder.accept(env);
            }
        } catch (Exception e) {
            log.error("[MockPy/{}] handle failed, requeue=false. messageId={}", tag, messageId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    // ============================================================
    // 事件回写（异步）
    // ============================================================

    private void schedulePlanResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String userText = String.valueOf(payload.getOrDefault("userText", ""));
        String hint = stringField(payload, "primaryIntentHint");

        String intent = hint != null && !hint.isBlank() ? hint : inferIntent(userText);
        Map<String, Object> slots = inferSlots(userText, intent);

        Map<String, Object> body = new HashMap<>();
        body.put("intent", intent);
        body.put("slots", slots);
        body.put("confidence", 0.95);

        scheduleEvent(cmd, VerlaAgentEventType.PLAN_INTENT_RESOLVED, body, 200);
    }

    private void scheduleAgentResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String agentType = String.valueOf(payload.getOrDefault("agentType", "qa"));

        scheduleEvent(cmd, VerlaAgentEventType.AGENT_STARTED, Map.of("agentType", agentType), 50);

        List<String> chunks = mockChunks(agentType);
        for (int i = 0; i < chunks.size(); i++) {
            int idx = i;
            Map<String, Object> chunkPayload = new HashMap<>();
            chunkPayload.put("delta", chunks.get(idx));
            chunkPayload.put("index", idx);
            scheduleEvent(cmd, VerlaAgentEventType.AGENT_STEP_STREAM_CHUNK,
                    chunkPayload, 200L + idx * 200L);
        }

        Map<String, Object> doneBody = new HashMap<>();
        doneBody.put("summary", "[Mock] " + agentType + " 已完成");
        doneBody.put("artifactCount", 0);
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_COMPLETED, doneBody,
                300L + chunks.size() * 200L);
    }

    private void scheduleControlResponse(VerlaCommandEnvelope cmd) {
        if (VerlaCommandAction.CMD_AGENT_CANCEL.getCode().equals(cmd.getAction())) {
            scheduleEvent(cmd, VerlaAgentEventType.AGENT_CANCELLED,
                    Map.of("reason", "user_cancelled"), 100);
        } else {
            log.debug("[MockPy/CONTROL] action={} no event response (mock)", cmd.getAction());
        }
    }

    /** 把事件构造好后扔给调度器，延迟 N ms 异步发布 */
    private void scheduleEvent(VerlaCommandEnvelope cmd, VerlaAgentEventType type,
                               Map<String, Object> payload, long delayMs) {
        scheduler.schedule(() -> publishEvent(cmd, type, payload),
                Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void publishEvent(VerlaCommandEnvelope cmd, VerlaAgentEventType type,
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

    // ============================================================
    // 业务模拟
    // ============================================================

    /** 极简意图判定：仅做关键词匹配，目的是让本地 demo 走通；真 Py 用 LLM。 */
    private static String inferIntent(String userText) {
        if (userText == null || userText.isBlank()) {
            return "qa";
        }
        String t = userText.toLowerCase(Locale.ROOT);
        if (containsAny(t, "作业", "习题", "题目", "做题", "解题", "homework", "assignment")) {
            return "assignment";
        }
        if (containsAny(t, "翻译", "翻成", "translate")) {
            return "translate";
        }
        if (containsAny(t, "总结", "归纳", "summary", "summarize")) {
            return "summary";
        }
        if (containsAny(t, "材料", "资料", "materials")) {
            return "materials";
        }
        return "qa";
    }

    private static Map<String, Object> inferSlots(String userText, String intent) {
        Map<String, Object> slots = new HashMap<>();
        if (userText == null) return slots;
        String t = userText.toLowerCase(Locale.ROOT);
        if ("assignment".equals(intent)) {
            String subject = null;
            if (containsAny(t, "物理", "physics")) subject = "physics";
            else if (containsAny(t, "数学", "math", "maths")) subject = "math";
            else if (containsAny(t, "化学", "chemistry")) subject = "chemistry";
            else if (containsAny(t, "英语", "english")) subject = "english";
            else if (containsAny(t, "语文", "chinese")) subject = "chinese";
            if (subject != null) slots.put("subject", subject);
            slots.put("kind", "homework");
        }
        return slots;
    }

    private static List<String> mockChunks(String agentType) {
        List<String> out = new ArrayList<>();
        if ("assignment".equals(agentType)) {
            out.add("# 物理作业（Mock）\n\n");
            out.add("## 第 1 题\n这是模拟的解题过程……\n");
            out.add("## 总结\n以上即为模拟回答，完整解答请等真实 Py 服务接入。\n");
        } else {
            out.add("[Mock] 这是 ");
            out.add(agentType);
            out.add(" agent 的模拟流式输出。\n");
        }
        return out;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // helpers
    // ============================================================

    private VerlaCommandEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, VerlaCommandEnvelope.class);
        } catch (Exception e) {
            log.warn("[MockPy] envelope parse failed, body.size={}", body.length());
            return null;
        }
    }

    private static String stringHeader(MessageProperties props, String name) {
        Object v = props.getHeaders().get(name);
        return v == null ? null : v.toString();
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
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

    private static Long refConvId(VerlaEventEnvelope env) {
        return env == null || env.getConversation() == null ? null : env.getConversation().getConversationId();
    }

    private static Long refTurnId(VerlaEventEnvelope env) {
        return env == null || env.getTurn() == null ? null : env.getTurn().getTurnId();
    }
}
