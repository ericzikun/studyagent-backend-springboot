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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock Py command consumer（dev / local profile）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §18 PR-11、docs/verla-端到端调用链路-做作业示例.md §3、
 * docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §5.3。
 * <p>
 * 职责：
 * <ol>
 *   <li>验证 Java→Py 全链路：mq_outbox → OutboxDispatchScheduler →
 *       studyagent.command exchange → verla.cmd.* 队列。</li>
 *   <li>解耦 Py 服务联调：收到命令后异步把事件回推到 {@link VerlaRabbitConfig#EVENTS_EXCHANGE}，
 *       让 Java 侧 inbox/cursor/handler 跑完一整个 happy path。</li>
 * </ol>
 * <p>
 * 模拟时序（与文档 §6 / §11.5 / V2 §4 一致）：
 * <pre>
 * cmd.plan.intent      ──► (200ms)  PLAN_INTENT_RESOLVED { intent, slots }
 *                       └── 30% 概率走 clarify 分支：
 *                             (150ms)  AGENT_CLARIFY_FORM_ISSUED { formId, schema }
 *                             (250ms)  PLAN_NEEDS_CLARIFY { question }
 * cmd.assignment.init  ──► (50ms)   ASSIGNMENT_INIT_STARTED
 *                          (200ms)  ASSIGNMENT_INIT_STREAM_CHUNK
 *                          (600ms)  ASSIGNMENT_INIT_COMPLETED { requirementUnderstanding, ready=false }
 *                          (850ms)  ASSIGNMENT_DEEP_UNDERSTANDING_STARTED
 *                          (1000ms) ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK
 *                          (1450ms) ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED { ready=true }
 * cmd.assignment.deep_understanding
 *                    ──► (50ms)   ASSIGNMENT_DEEP_UNDERSTANDING_STARTED
 *                          (200ms)  ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK
 *                          (600ms)  ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED { ready / isReadyForGeneration }
 *                              或 ASSIGNMENT_CLARIFY_FORM_READY { requirementForm }
 * cmd.assignment.run  ──► (50ms)   ASSIGNMENT_AGENT_FLOW_STARTED
 *                          (80ms)   ASSIGNMENT_AGENT_NODE_UPDATED plan=RUNNING
 *                          (500ms)  ASSIGNMENT_AGENT_NODE_UPDATED plan=COMPLETED + queued task nodes
 *                          (900ms+) ASSIGNMENT_AGENT_NODE_UPDATED each task running/completed
 *                          (6600ms) ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED
 *                          (9000ms) ASSIGNMENT_AGENT_FLOW_COMPLETED
 * cmd.agent.run        ──► (50ms)   AGENT_STARTED
 *                          (150ms)  AGENT_TOOL_CALL_RECORDED  { tool=web_search, status=RUNNING }
 *                          (200ms)  AGENT_STEP_STREAM_CHUNK   { delta: "片段 1 ..." }
 *                          (350ms)  AGENT_TOOL_CALL_RECORDED  { tool=web_search, status=SUCCEEDED }
 *                          (400ms)  AGENT_STEP_STREAM_CHUNK   { delta: "片段 2 ..." }
 *                          (600ms)  AGENT_STEP_STREAM_CHUNK   { delta: "片段 3 ..." }
 *                          (800ms)  AGENT_ARTIFACT_UPDATED    { artifactUid, kind=assignment_card, status=READY }
 *                          (900ms)  AGENT_COMPLETED           { summary, artifactCount=1 }
 * cmd.agent.control.cancel  ──► (100ms) AGENT_CANCELLED
 * cmd.agent.control.retry   ──► no-op (留给真 Py 处理)
 * cmd.clarify.submit        ──► (200ms) PLAN_INTENT_RESOLVED { intent, slots from answers }
 * cmd.attachment.parse      ──► (200ms) ATTACHMENT_PARSED status=PARSING progress=30
 *                              (500ms) ATTACHMENT_PARSED status=PARSING progress=70
 *                              (700ms) AGENT_ARTIFACT_UPDATED { kind=document_markdown }
 *                              (900ms) ATTACHMENT_PARSED status=PARSED summary primaryArtifactUid
 * </pre>
 * <p>
 * 仅在 {@code spring.profiles.active in (dev, local)} 时启用，生产环境一定不能启动此 bean。
 */
@Slf4j
@Component
@Profile({"dev", "local"})
@ConditionalOnProperty(name = "verla.mq.mock.enabled", havingValue = "true", matchIfMissing = false)
public class MockPyCommandConsumer {

    private static final String PRODUCER_SERVICE = "py-mock";

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final VerlaRabbitConfig rabbitConfig;
    private final ScheduledExecutorService scheduler;
    /** 按 sessionId 维护事件 seq，保证同 session 内单调递增 */
    private final ConcurrentHashMap<Long, AtomicLong> sessionSeq = new ConcurrentHashMap<>();
    /** 按 turn 记录 deep understanding 轮次，用来模拟“多轮后可开始生成”的 composer 按钮信号。 */
    private final ConcurrentHashMap<Long, AtomicLong> assignmentDeepUnderstandingRounds = new ConcurrentHashMap<>();
    private final String instanceId;

    /** plan agent 触发 clarify form 的概率 (0~100)。可通过 verla.mq.mock.clarify-rate 配置。 */
    private final int clarifyRate;

    public MockPyCommandConsumer(ObjectMapper objectMapper,
                                 RabbitTemplate rabbitTemplate,
                                 VerlaRabbitConfig rabbitConfig,
                                 @Value("${verla.mq.mock.delay-base-ms:50}") long delayBaseMs,
                                 @Value("${verla.mq.mock.clarify-rate:30}") int clarifyRate) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitConfig = rabbitConfig;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "verla-mock-event-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.instanceId = "local-" + Long.toHexString(System.nanoTime() & 0xffffffL);
        this.clarifyRate = Math.max(0, Math.min(100, clarifyRate));
        log.info("[MockPy] enabled, instanceId={}, delayBaseMs={}, clarifyRate={}%",
                instanceId, delayBaseMs, this.clarifyRate);
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

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_CLARIFY_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onClarify(Message msg, Channel channel) throws IOException {
        handle("CLARIFY", msg, channel, this::scheduleClarifyResponse);
    }

    @RabbitListener(
            queues = VerlaRabbitConfig.CMD_ATTACHMENT_QUEUE,
            containerFactory = "verlaListenerContainerFactory"
    )
    public void onAttachment(Message msg, Channel channel) throws IOException {
        handle("ATTACHMENT", msg, channel, this::scheduleAttachmentResponse);
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

        // V2: 30% 概率走 clarify 分支（关键词触发可强制开/关）
        if (shouldClarify(userText)) {
            String formId = "form_mock_" + UUID.randomUUID().toString().substring(0, 8);
            scheduleEvent(cmd, VerlaAgentEventType.AGENT_CLARIFY_FORM_ISSUED,
                    buildClarifyFormPayload(formId, intent), 150);
            scheduleEvent(cmd, VerlaAgentEventType.PLAN_NEEDS_CLARIFY,
                    buildPlanClarifyHintPayload(formId, intent), 250);
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("intent", intent);
        body.put("slots", slots);
        body.put("confidence", 0.95);

        scheduleEvent(cmd, VerlaAgentEventType.PLAN_INTENT_RESOLVED, body, 200);
    }

    private boolean shouldClarify(String userText) {
        if (userText != null) {
            String t = userText.toLowerCase(Locale.ROOT);
            if (containsAny(t, "再问", "请追问", "clarify", "ask me")) return true;
            if (containsAny(t, "不要追问", "no clarify", "skip clarify")) return false;
        }
        return clarifyRate > 0 && ThreadLocalRandom.current().nextInt(100) < clarifyRate;
    }

    private Map<String, Object> buildClarifyFormPayload(String formId, String intent) {
        Map<String, Object> p = new HashMap<>();
        p.put("formId", formId);
        p.put("title", "请补充以下信息");
        p.put("description", "为了更好地帮你处理「" + intent + "」，需要先确认几个问题");

        List<Map<String, Object>> schema = new ArrayList<>();
        schema.add(Map.of(
                "key", "subject",
                "label", "学科",
                "type", "select",
                "options", List.of("math", "physics", "chemistry", "english", "chinese"),
                "required", true));
        schema.add(Map.of(
                "key", "deadline",
                "label", "截止时间",
                "type", "date",
                "required", false));
        schema.add(Map.of(
                "key", "extraNote",
                "label", "其他说明",
                "type", "textarea",
                "required", false));
        p.put("schema", schema);
        return p;
    }

    private Map<String, Object> buildPlanClarifyHintPayload(String formId, String intent) {
        Map<String, Object> p = new HashMap<>();
        p.put("question", "请先填写上方表单，回答后我会继续处理「" + intent + "」");
        p.put("formId", formId);
        return p;
    }

    private void scheduleAgentResponse(VerlaCommandEnvelope cmd) {
        if (VerlaCommandAction.CMD_DETECTION_RUN.getCode().equals(cmd.getAction())) {
            scheduleCapabilityMock(cmd, VerlaCommandAction.CMD_DETECTION_RUN);
            return;
        }
        if (VerlaCommandAction.CMD_HUMANIZER_RUN.getCode().equals(cmd.getAction())) {
            scheduleCapabilityMock(cmd, VerlaCommandAction.CMD_HUMANIZER_RUN);
            return;
        }
        if (VerlaCommandAction.CMD_ASSIGNMENT_INIT.getCode().equals(cmd.getAction())) {
            scheduleAssignmentInitResponse(cmd);
            return;
        }
        if (VerlaCommandAction.CMD_ASSIGNMENT_DEEP_UNDERSTANDING.getCode().equals(cmd.getAction())) {
            scheduleAssignmentDeepUnderstandingResponse(cmd);
            return;
        }
        if (VerlaCommandAction.CMD_ASSIGNMENT_CLARIFY.getCode().equals(cmd.getAction())) {
            scheduleAssignmentClarifyResponse(cmd);
            return;
        }
        if (VerlaCommandAction.CMD_FILE_CHAT.getCode().equals(cmd.getAction())) {
            scheduleFileChatResponse(cmd);
            return;
        }
        if (VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode().equals(cmd.getAction())) {
            scheduleAssignmentRunResponse(cmd);
            return;
        }

        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String agentType = String.valueOf(payload.getOrDefault("agentType", "qa"));

        scheduleEvent(cmd, VerlaAgentEventType.AGENT_STARTED, Map.of("agentType", agentType), 50);

        // V2: 插入 1 次 user-visible 工具调用 trace（RUNNING → SUCCEEDED 同 callId 幂等）
        String toolCallId = "call_mock_" + UUID.randomUUID().toString().substring(0, 8);
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_TOOL_CALL_RECORDED,
                buildToolCallPayload(toolCallId, agentType, "RUNNING", null, null), 150);

        List<String> chunks = mockChunks(agentType);
        for (int i = 0; i < chunks.size(); i++) {
            int idx = i;
            Map<String, Object> chunkPayload = new HashMap<>();
            chunkPayload.put("delta", chunks.get(idx));
            chunkPayload.put("index", idx);
            scheduleEvent(cmd, VerlaAgentEventType.AGENT_STEP_STREAM_CHUNK,
                    chunkPayload, 200L + idx * 200L);
        }

        scheduleEvent(cmd, VerlaAgentEventType.AGENT_TOOL_CALL_RECORDED,
                buildToolCallPayload(toolCallId, agentType, "SUCCEEDED", 200, "返回 5 条相关结果"),
                350);

        // V2: 在 completed 之前发 artifact_updated（让前端右栏材料能渲染）
        String artifactUid = "artifact_mock_" + UUID.randomUUID().toString().substring(0, 8);
        long artifactDelay = 250L + chunks.size() * 200L;
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_ARTIFACT_UPDATED,
                buildArtifactPayload(artifactUid, agentType), artifactDelay);

        Map<String, Object> doneBody = new HashMap<>();
        doneBody.put("summary", "[Mock] " + agentType + " 已完成");
        doneBody.put("artifactCount", 1);
        doneBody.put("primaryArtifactUid", artifactUid);
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_COMPLETED, doneBody,
                300L + chunks.size() * 200L);
    }

    private void scheduleFileChatResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String objectId = stringField(payload, "objectId");
        String message = stringField(payload, "message");
        if (objectId == null || objectId.isBlank()) {
            objectId = "att_mock_file";
        }
        if (message == null || message.isBlank()) {
            message = "请帮我理解这个文件。";
        }

        String finalText = "我先帮你结合当前作业上下文理解这个文件。这是一次本地 mock 返回，正式内容会由 Python 文件对话 agent 生成。";
        scheduleEvent(cmd, VerlaAgentEventType.FILE_CHAT_STARTED, Map.of("objectId", objectId), 50);
        scheduleEvent(cmd, VerlaAgentEventType.FILE_CHAT_STREAM_CHUNK,
                Map.of("objectId", objectId, "delta", "我先帮你结合当前作业上下文理解这个文件。"), 180);
        scheduleEvent(cmd, VerlaAgentEventType.FILE_CHAT_STREAM_CHUNK,
                Map.of("objectId", objectId, "delta", " 这是一次本地 mock 返回，正式内容会由 Python 文件对话 agent 生成。"), 320);
        scheduleEvent(cmd, VerlaAgentEventType.FILE_CHAT_COMPLETED,
                Map.of(
                        "objectId", objectId,
                        "finalText", finalText,
                        "echoMessage", message
                ), 520);
    }

    /**
     * Assignment stage 0 mock：按前端 V2 mapper 已支持的 ASSIGNMENT_INIT_* 协议发事件。
     * 这样本地 smoke 能验证 Java SSE 和 assignment understanding 状态，而不是落回通用 AGENT_*。
     */
    private void scheduleAssignmentInitResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> started = new HashMap<>();
        started.put("agentType", assignmentAgentType(cmd));
        started.put("stage", "stage_0");
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_INIT_STARTED, started, 50);

        scheduleAssignmentChunks(cmd, VerlaAgentEventType.ASSIGNMENT_INIT_STREAM_CHUNK,
                List.of(
                        "Reading the assignment brief and extracting the core requirements...\n",
                        "I found the topic, expected output, and the constraints that still need confirmation.\n"),
                null,
                200);

        Map<String, Object> done = new HashMap<>();
        done.put("summary", "[Mock] Assignment requirements understood");
        done.put("ready", false);
        done.put("isReadyForGeneration", false);
        done.put("nextActions", List.of("deep_understanding", "generation"));
        done.put("requirementUnderstanding", Map.of(
                "topic", "Causes of World War I",
                "outputType", "short outline",
                "nextStep", "confirm requirements before generation"));
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_INIT_COMPLETED, done, 650);
        scheduleInitialDeepUnderstandingReady(cmd, 850);
    }

    /**
     * Local MockPy only: Java 当前只会为首轮 assignment clarify 派发 stage_0 命令。
     * 真实 Py / 后续真实编排应单独产出 deep-understanding ready 信号；本地 mock
     * 在 init 后追加同一段事件，避免前端按 ready 字段改造后停在 init completed。
     */
    private void scheduleInitialDeepUnderstandingReady(VerlaCommandEnvelope cmd, long startedDelayMs) {
        markAssignmentDeepUnderstandingRound(cmd);
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
                Map.of("agentType", assignmentAgentType(cmd), "userUnderstood", false, "mockAutoPreview", true),
                startedDelayMs);
        scheduleAssignmentChunks(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK,
                List.of(
                        "Connecting the extracted requirements into a clearer assignment plan...\n",
                        "The mock brief is ready for you to choose walkthrough or assignment setup.\n"),
                null,
                startedDelayMs + 150);

        Map<String, Object> done = new HashMap<>();
        done.put("summary", "[Mock] Deep understanding ready");
        done.put("userUnderstood", false);
        done.put("ready", true);
        done.put("isReadyForGeneration", false);
        done.put("nextActions", List.of("deep_understanding", "generation"));
        done.put("mockAutoPreview", true);
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED, done,
                startedDelayMs + 600);
    }

    /**
     * Assignment stage 2 mock：保留 userUnderstood 分支，方便后续 smoke 覆盖“继续追问/进入表单”。
     */
    private void scheduleAssignmentDeepUnderstandingResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        boolean userUnderstood = Boolean.TRUE.equals(payload.get("userUnderstood"));
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STARTED,
                Map.of("agentType", assignmentAgentType(cmd), "userUnderstood", userUnderstood), 50);

        scheduleAssignmentChunks(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_STREAM_CHUNK,
                List.of(
                        "Deepening the requirement interpretation and checking for missing rubric details...\n",
                        "The brief is ready to move into a structured clarification form.\n"),
                null,
                200);

        boolean showStartGenerationPrompt = shouldShowStartGenerationPrompt(cmd, payload, userUnderstood);
        boolean showReadyCard = !userUnderstood && !showStartGenerationPrompt;

        if (userUnderstood) {
            Map<String, Object> done = new HashMap<>();
            done.put("summary", "[Mock] Clarifying form ready");
            done.put("ready", true);
            done.put("nextActions", assignmentDeepUnderstandingNextActions(true, false));
            done.put("requirementForm", buildMockRequirementForm());
            done.put("appendAsk", buildMockAppendAsk());
            scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_CLARIFY_FORM_READY, done, 650);
            return;
        }

        Map<String, Object> done = new HashMap<>();
        done.put("summary", "[Mock] Deep understanding completed");
        done.put("userUnderstood", false);
        done.put("ready", showReadyCard);
        done.put("isReadyForGeneration", showStartGenerationPrompt);
        done.put("nextActions", assignmentDeepUnderstandingNextActions(false, showStartGenerationPrompt));
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_DEEP_UNDERSTANDING_COMPLETED, done, 650);
    }

    /**
     * 真实 Py 会根据多轮理解质量决定何时展示 composer 上方的开始生成按钮。
     * MockPy 没有 LLM 判断，所以用同一 turn 内第二次及之后的 deep understanding
     * 来模拟“聊了多轮但还没进入 setup”的场景；也支持 payload 显式覆盖，方便局部 smoke。
     */
    private boolean shouldShowStartGenerationPrompt(VerlaCommandEnvelope cmd,
                                                    Map<String, Object> payload,
                                                    boolean userUnderstood) {
        if (userUnderstood) {
            return false;
        }
        Boolean explicit = boolField(payload, "isReadyForGeneration");
        if (explicit != null) {
            return explicit;
        }
        Long key = refTurnId(cmd);
        if (key == null) {
            key = refSessionId(cmd);
        }
        if (key == null) {
            return false;
        }
        long round = assignmentDeepUnderstandingRounds
                .computeIfAbsent(key, ignored -> new AtomicLong(0L))
                .incrementAndGet();
        return round > 1;
    }

    private void markAssignmentDeepUnderstandingRound(VerlaCommandEnvelope cmd) {
        Long key = refTurnId(cmd);
        if (key == null) {
            key = refSessionId(cmd);
        }
        if (key == null) {
            return;
        }
        assignmentDeepUnderstandingRounds
                .computeIfAbsent(key, ignored -> new AtomicLong(0L))
                .incrementAndGet();
    }

    private List<String> assignmentDeepUnderstandingNextActions(boolean userUnderstood,
                                                                 boolean showStartGenerationPrompt) {
        if (userUnderstood) {
            return List.of("finalize");
        }
        if (showStartGenerationPrompt) {
            return List.of("generation");
        }
        return List.of("deep_understanding", "generation");
    }

    /**
     * Assignment clarify mock：覆盖 finalize happy path。
     * <p>
     * 当前 split clarify 契约下，{@code cmd.assignment.clarify} 由前端
     * {@code /assignment/clarify/finalize} 触发；Java 侧只在
     * {@code isReadyForGeneration=true} 时自动派发 {@code cmd.assignment.run}。
     * MockPy 必须给出这个终态信号，才能验证本地 Spring→MQ→MockPy→SSE→前端完成链路。
     */
    private void scheduleAssignmentClarifyResponse(VerlaCommandEnvelope cmd) {
        String stage = "stage_3";
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        Map<String, Object> requirementForm = mapField(payload, "requirementForm");
        if (requirementForm.isEmpty()) {
            requirementForm = mapField(payload, "reservedFields");
        }
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_CLARIFY_STARTED,
                Map.of("agentType", assignmentAgentType(cmd), "stage", stage), 50);

        scheduleAssignmentChunks(cmd, VerlaAgentEventType.ASSIGNMENT_CLARIFY_STREAM_CHUNK,
                List.of(
                        "Finalizing the confirmed assignment requirements...\n",
                        "The assignment setup is ready. I am starting the generation workflow.\n"),
                stage,
                200);

        Map<String, Object> done = new HashMap<>();
        done.put("stage", stage);
        done.put("summary", "[Mock] Assignment requirements finalized");
        done.put("isReadyForGeneration", true);
        done.put("requirementForm", requirementForm);
        done.put("appendAskAnswers", payload.getOrDefault("appendAskAnswers", List.of()));
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_CLARIFY_COMPLETED, done, 650);
    }

    /**
     * Assignment 正式生成 mock：发出前端 workflow canvas 所需的 node 快照事件。
     * <p>
     * 这些事件只服务本地样式/链路联调；真实 Py 也应按同一 payload 形状发
     * {@code ASSIGNMENT_AGENT_NODE_UPDATED}，前端不会再补 fallback 任务卡片。
     */
    private void scheduleAssignmentRunResponse(VerlaCommandEnvelope cmd) {
        String agentType = assignmentAgentType(cmd);
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_STARTED,
                Map.of("agentType", agentType, "stage", "assignment_run"), 50);

        scheduleAssignmentNode(cmd,
                "assignment-plan",
                "Make plan",
                "Planning",
                "RUNNING",
                0,
                "Analyze the confirmed requirements and decompose the work into executable tasks.",
                "Inputs include the final requirement form, user answers, and uploaded materials.",
                null,
                List.of(
                        workflowStep("parse-requirements", "Parse confirmed requirements", "COMPLETED",
                                "Read subject, length, format, rubric constraints, and attachment context."),
                        workflowStep("decompose-tasks", "Decompose task sequence", "RUNNING",
                                "Create the task chain for research, outline, drafting, citation, and QA.")),
                80);

        scheduleAssignmentNode(cmd,
                "assignment-plan",
                "Make plan",
                "Planning",
                "COMPLETED",
                0,
                "Analyze the confirmed requirements and decompose the work into executable tasks.",
                "Inputs include the final requirement form, user answers, and uploaded materials.",
                "Prepared the ordered execution plan for all downstream task cards.",
                List.of(
                        workflowStep("parse-requirements", "Parse confirmed requirements", "COMPLETED",
                                "Read subject, length, format, rubric constraints, and attachment context."),
                        workflowStep("decompose-tasks", "Decompose task sequence", "COMPLETED",
                                "Create the task chain for research, outline, drafting, citation, and QA.")),
                500);

        scheduleQueuedAssignmentNodes(cmd, 520);

        scheduleAssignmentTaskLifecycle(cmd, "problem-solving-expert", "Problem Solving Expert",
                "Problem Solving Expert", 1,
                "Translate the prompt into a thesis direction and decide what evidence is needed.",
                "Problem frame, constraints, deliverable type, and user-provided context.",
                "Locked the argument direction and evidence plan.",
                List.of(
                        "Intent Parsing & Query Expansion",
                        "Constraint Matching",
                        "Evidence Need Mapping"),
                900, 1500);
        scheduleAssignmentTaskLifecycle(cmd, "evidence-researcher", "Evidence Researcher",
                "Research", 2,
                "Collect and filter useful facts, references, and source notes for the assignment.",
                "Evidence plan, uploaded materials, and citation expectations.",
                "Prepared supporting notes and candidate references for drafting.",
                List.of(
                        "Source discovery",
                        "Evidence filtering",
                        "Citation note capture"),
                1600, 2300);
        scheduleAssignmentTaskLifecycle(cmd, "outline-architect", "Outline Architect",
                "Outline", 3,
                "Build the assignment structure before writing the full response.",
                "Thesis direction, selected evidence, and required length.",
                "Produced a section-level outline ready for drafting.",
                List.of(
                        "Thesis placement",
                        "Section sequencing",
                        "Paragraph target sizing"),
                2400, 3100);
        scheduleAssignmentTaskLifecycle(cmd, "draft-writer", "Draft Writer",
                "Writing", 4,
                "Write the assignment content section by section.",
                "Outline, evidence notes, and formatting requirements.",
                "Generated the main assignment draft.",
                List.of(
                        "Introduction draft",
                        "Body sections",
                        "Conclusion draft"),
                3200, 4300);
        scheduleAssignmentTaskLifecycle(cmd, "citation-reviewer", "Citation Reviewer",
                "Citation", 5,
                "Review citations and flag unsupported claims.",
                "Draft text, source notes, and requested citation style.",
                "Aligned claims with source notes and citation placeholders.",
                List.of(
                        "Claim support check",
                        "Citation style pass",
                        "Unsupported claim cleanup"),
                4400, 5400);
        scheduleAssignmentTaskLifecycle(cmd, "quality-check", "Quality Check",
                "QA", 6,
                "Run a final rubric and readability check before returning the artifact.",
                "Draft package, citation notes, and deliverable requirements.",
                "Confirmed the generated assignment is ready for review.",
                List.of(
                        "Rubric coverage",
                        "Readability polish",
                        "Final package check"),
                5500, 7000);

        scheduleAssignmentChunks(cmd, VerlaAgentEventType.AGENT_STEP_STREAM_CHUNK,
                List.of(
                        "I am turning the confirmed plan into a connected task workflow now.\n",
                        "Research, outline, draft, citation, and QA tasks are progressing one by one.\n"),
                "assignment_run",
                1200);

        String artifactUid = "assignment_mock_document_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> art = new HashMap<>();
        art.put("artifactUid", artifactUid);
        art.put("kind", "document_markdown");
        art.put("mime", "text/markdown");
        art.put("summary", "Generated Assignment.md");
        art.put("bodyOrRef", "# Mock Generated Assignment\n\n"
                + "This local MockPy document is emitted after workflow node events so the V2 right rail can be tested against the real SSE path.\n");
        art.put("status", "READY");
        art.put("version", 1);
        art.put("sizeBytes", 512L);
        art.put("meta", Map.of("agent", agentType, "source", "mockpy-assignment-run"));
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_ARTIFACT_UPDATED, art, 6600);

        Map<String, Object> done = new HashMap<>();
        done.put("summary", "[Mock] Assignment workflow completed");
        done.put("artifactCount", 1);
        done.put("primaryArtifactUid", artifactUid);
        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_AGENT_FLOW_COMPLETED, done, 9000);
    }

    private void scheduleQueuedAssignmentNodes(VerlaCommandEnvelope cmd, long firstDelayMs) {
        List<AssignmentNodeSeed> nodes = List.of(
                new AssignmentNodeSeed("problem-solving-expert", "Problem Solving Expert", "Problem Solving Expert", 1,
                        "Translate the prompt into a thesis direction and decide what evidence is needed."),
                new AssignmentNodeSeed("evidence-researcher", "Evidence Researcher", "Research", 2,
                        "Collect and filter useful facts, references, and source notes for the assignment."),
                new AssignmentNodeSeed("outline-architect", "Outline Architect", "Outline", 3,
                        "Build the assignment structure before writing the full response."),
                new AssignmentNodeSeed("draft-writer", "Draft Writer", "Writing", 4,
                        "Write the assignment content section by section."),
                new AssignmentNodeSeed("citation-reviewer", "Citation Reviewer", "Citation", 5,
                        "Review citations and flag unsupported claims."),
                new AssignmentNodeSeed("quality-check", "Quality Check", "QA", 6,
                        "Run a final rubric and readability check before returning the artifact."));

        for (int i = 0; i < nodes.size(); i++) {
            AssignmentNodeSeed node = nodes.get(i);
            scheduleAssignmentNode(cmd,
                    node.id(),
                    node.title(),
                    node.role(),
                    "QUEUED",
                    node.order(),
                    node.summary(),
                    null,
                    null,
                    List.of(workflowStep("queued", "Waiting for previous task", "QUEUED",
                            "This task will start after the upstream card completes.")),
                    firstDelayMs + i * 40L);
        }
    }

    private void scheduleAssignmentTaskLifecycle(VerlaCommandEnvelope cmd,
                                                 String id,
                                                 String title,
                                                 String role,
                                                 int order,
                                                 String summary,
                                                 String inputSummary,
                                                 String outputSummary,
                                                 List<String> stepTitles,
                                                 long runningDelayMs,
                                                 long completedDelayMs) {
        scheduleAssignmentNode(cmd, id, title, role, "RUNNING", order, summary,
                inputSummary, null,
                workflowSteps(stepTitles, "RUNNING"),
                runningDelayMs);
        scheduleAssignmentNode(cmd, id, title, role, "COMPLETED", order, summary,
                inputSummary, outputSummary,
                workflowSteps(stepTitles, "COMPLETED"),
                completedDelayMs);
    }

    private void scheduleAssignmentNode(VerlaCommandEnvelope cmd,
                                        String id,
                                        String title,
                                        String role,
                                        String status,
                                        int order,
                                        String summary,
                                        String inputSummary,
                                        String outputSummary,
                                        List<Map<String, Object>> steps,
                                        long delayMs) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("title", title);
        node.put("role", role);
        node.put("status", status);
        node.put("order", order);
        node.put("summary", summary);
        node.put("subtitle", order == 0 ? "Start" : "AI Agent: " + role);
        if (inputSummary != null && !inputSummary.isBlank()) {
            node.put("inputSummary", inputSummary);
        }
        if (outputSummary != null && !outputSummary.isBlank()) {
            node.put("outputSummary", outputSummary);
        }
        if (steps != null && !steps.isEmpty()) {
            node.put("steps", steps);
        }

        scheduleEvent(cmd, VerlaAgentEventType.ASSIGNMENT_AGENT_NODE_UPDATED,
                Map.of("node", node), delayMs);
    }

    private List<Map<String, Object>> workflowSteps(List<String> titles, String status) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            steps.add(workflowStep(
                    "step-" + (i + 1),
                    titles.get(i),
                    status,
                    statusDescription(status, i, titles.size())));
        }
        return steps;
    }

    private Map<String, Object> workflowStep(String id, String title, String status, String description,
                                             String... resources) {
        Map<String, Object> step = new HashMap<>();
        step.put("id", id);
        step.put("title", title);
        step.put("status", status);
        step.put("description", description);
        if (resources != null && resources.length > 0) {
            step.put("resources", List.of(resources));
        }
        return step;
    }

    private String statusDescription(String status, int index, int total) {
        if ("COMPLETED".equals(status)) {
            return "Completed step " + (index + 1) + " of " + total + ".";
        }
        if ("QUEUED".equals(status)) {
            return "Waiting to start step " + (index + 1) + " of " + total + ".";
        }
        return "Running step " + (index + 1) + " of " + total + ".";
    }

    private record AssignmentNodeSeed(String id, String title, String role, int order, String summary) {
    }

    private Map<String, Object> mapField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private void scheduleAssignmentChunks(VerlaCommandEnvelope cmd, VerlaAgentEventType eventType,
                                          List<String> chunks, String stage, long firstDelayMs) {
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunkPayload = new HashMap<>();
            chunkPayload.put("delta", chunks.get(i));
            chunkPayload.put("channel", "content");
            chunkPayload.put("index", i);
            if (stage != null) {
                chunkPayload.put("stage", stage);
            }
            scheduleEvent(cmd, eventType, chunkPayload, firstDelayMs + i * 200L);
        }
    }

    private Map<String, Object> buildMockRequirementForm() {
        return Map.of(
                "title", "Assignment requirements",
                "description", "Confirm the details before generation.",
                "schema", List.of(
                        Map.of("key", "subject", "label", "Subject", "type", "text", "required", true),
                        Map.of("key", "format", "label", "Expected format", "type", "text", "required", true),
                        Map.of("key", "deadline", "label", "Deadline", "type", "date", "required", false)));
    }

    private Map<String, Object> buildMockAppendAsk() {
        return Map.of(
                "questions", List.of(
                        Map.of(
                                "id", "mockpy_target_audience",
                                "index", 1,
                                "question", "Who should this assignment be written for?",
                                "placeholder", "Example: first-year business students, course instructor, or general readers",
                                "required", true,
                                "requires_file_upload", false,
                                "allowExpandedEditor", true,
                                "order", 1),
                        Map.of(
                                "id", "mockpy_rubric_focus",
                                "index", 2,
                                "question", "Paste any rubric details or grading priorities that should shape the response.",
                                "placeholder", "Example: prioritize primary sources, compare two viewpoints, include citations",
                                "required", false,
                                "requires_file_upload", true,
                                "allowExpandedEditor", true,
                                "order", 2),
                        Map.of(
                                "id", "mockpy_constraints",
                                "index", 3,
                                "question", "Any constraints I should avoid or respect?",
                                "placeholder", "Example: avoid first person, use APA, no bullet points",
                                "required", false,
                                "requires_file_upload", false,
                                "allowExpandedEditor", true,
                                "order", 3)));
    }

    private String assignmentAgentType(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String agentType = String.valueOf(payload.getOrDefault("agentType", "assignment"));
        return agentType.isBlank() ? "assignment" : agentType;
    }

    /** Mock：检测 / Humanizer — 简化为 STARTED → ARTIFACT → COMPLETED（与真 Py 事件形状接近）。 */
    private void scheduleCapabilityMock(VerlaCommandEnvelope cmd, VerlaCommandAction action) {
        String stage = action == VerlaCommandAction.CMD_DETECTION_RUN ? "ai_detection" : "ai_humanizer";
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_STARTED, Map.of("stage", stage), 50);

        String artifactUid = "artifact_mock_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> art = new HashMap<>();
        art.put("artifactUid", artifactUid);
        if (action == VerlaCommandAction.CMD_DETECTION_RUN) {
            art.put("kind", "ai_detection_report");
            art.put("mime", "application/json");
            art.put("summary", "[Mock] AI detection");
            art.put("bodyOrRef", "{\"probability\":0.12,\"label\":\"Human Written\"}");
        } else {
            art.put("kind", "humanizer_result");
            art.put("mime", "text/plain");
            art.put("summary", "[Mock] Humanized text");
            art.put("bodyOrRef", "[Mock] rewritten paragraph.");
        }
        art.put("status", "READY");
        art.put("version", 1);
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_ARTIFACT_UPDATED, art, 200);

        Map<String, Object> done = new HashMap<>();
        done.put("artifactUid", artifactUid);
        done.put("summary", "[Mock] " + action.getCode() + " done");
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_COMPLETED, done, 350);
    }

    private Map<String, Object> buildToolCallPayload(String callId, String agentType,
                                                    String status, Integer durationMs,
                                                    String summary) {
        Map<String, Object> p = new HashMap<>();
        p.put("toolCallId", callId);
        p.put("agentName", agentType);
        p.put("toolName", "web_search");
        p.put("status", status);
        p.put("visibility", "USER_VISIBLE");
        p.put("toolInput", Map.of("query", "[mock] 关于 " + agentType + " 的搜索"));
        if ("SUCCEEDED".equals(status)) {
            p.put("toolOutput", Map.of(
                    "resultCount", 5,
                    "topResults", List.of(
                            Map.of("title", "[mock] 结果 1", "url", "https://example.com/1"),
                            Map.of("title", "[mock] 结果 2", "url", "https://example.com/2"))));
            p.put("finishedAt", Instant.now().toString());
        } else {
            p.put("startedAt", Instant.now().toString());
        }
        if (durationMs != null) p.put("durationMs", durationMs);
        if (summary != null)    p.put("summary", summary);
        p.put("meta", Map.of("model", "mock-search-1", "tokens", 42));
        return p;
    }

    private Map<String, Object> buildArtifactPayload(String artifactUid, String agentType) {
        Map<String, Object> p = new HashMap<>();
        p.put("artifactUid", artifactUid);
        p.put("kind", "assignment".equalsIgnoreCase(agentType) ? "assignment_card" : "qa_summary");
        p.put("mime", "text/markdown");
        p.put("status", "READY");
        p.put("version", 1);
        p.put("summary", "[Mock] " + agentType + " 产物摘要");
        p.put("bodyOrRef", "# Mock 产物\n\n这是来自 MockPy 的产物正文（agentType=" + agentType + "）。");
        p.put("sizeBytes", 256L);
        p.put("meta", Map.of("agent", agentType, "model", "mock-llm-1"));
        return p;
    }

    private void scheduleControlResponse(VerlaCommandEnvelope cmd) {
        if (VerlaCommandAction.CMD_AGENT_CANCEL.getCode().equals(cmd.getAction())) {
            scheduleEvent(cmd, VerlaAgentEventType.AGENT_CANCELLED,
                    Map.of("reason", "user_cancelled"), 100);
        } else {
            log.debug("[MockPy/CONTROL] action={} no event response (mock)", cmd.getAction());
        }
    }

    /**
     * V2: 用户提交 clarify form → mock 直接当成"plan 拿到完整 slots"，
     * 200ms 后回 PLAN_INTENT_RESOLVED 让 turn 续跑（同 sessionId / correlationId）。
     */
    private void scheduleClarifyResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String formId = stringField(payload, "formId");
        String intent = stringField(payload, "intent");
        if (intent == null || intent.isBlank()) intent = "qa";

        Object answers = payload.get("answers");
        Map<String, Object> slots = new HashMap<>();
        if (answers instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) slots.put(e.getKey().toString(), e.getValue());
            }
        }
        slots.putIfAbsent("kind", "homework");

        Map<String, Object> body = new HashMap<>();
        body.put("intent", intent);
        body.put("slots", slots);
        body.put("confidence", 0.99);
        body.put("clarifiedFormId", formId);

        log.info("[MockPy/CLARIFY] formId={} → reply PLAN_INTENT_RESOLVED intent={} slots={}",
                formId, intent, slots.keySet());
        scheduleEvent(cmd, VerlaAgentEventType.PLAN_INTENT_RESOLVED, body, 200);
    }

    /**
     * V2: cmd.attachment.parse → 多阶段 ATTACHMENT_PARSED + 1 个 AGENT_ARTIFACT_UPDATED（document_markdown）。
     */
    private void scheduleAttachmentResponse(VerlaCommandEnvelope cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String objectId = stringField(payload, "objectId");
        if (objectId == null || objectId.isBlank()) {
            log.warn("[MockPy/ATTACHMENT] missing objectId in payload, skip");
            return;
        }
        String filename = stringField(payload, "filename");
        if (filename == null) filename = objectId;

        // PARSING progress=30
        Map<String, Object> p1 = new HashMap<>();
        p1.put("objectId", objectId);
        p1.put("status", "PARSING");
        p1.put("progress", 30);
        scheduleEvent(cmd, VerlaAgentEventType.ATTACHMENT_PARSED, p1, 200);

        // PARSING progress=70
        Map<String, Object> p2 = new HashMap<>();
        p2.put("objectId", objectId);
        p2.put("status", "PARSING");
        p2.put("progress", 70);
        scheduleEvent(cmd, VerlaAgentEventType.ATTACHMENT_PARSED, p2, 500);

        // 主产物（markdown 全文）
        String artifactUid = "artifact_doc_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> art = new HashMap<>();
        art.put("artifactUid", artifactUid);
        art.put("sourceObjectId", objectId);
        art.put("kind", "document_markdown");
        art.put("mime", "text/markdown");
        art.put("status", "READY");
        art.put("version", 1);
        art.put("summary", "[Mock] " + filename + " 解析完成");
        art.put("bodyOrRef", "# " + filename + "\n\n[mock] 这是模拟解析后的 markdown 全文。\n");
        art.put("sizeBytes", 1024L);
        art.put("meta", Map.of("page_count", 3));
        scheduleEvent(cmd, VerlaAgentEventType.AGENT_ARTIFACT_UPDATED, art, 700);

        // PARSED 终态
        Map<String, Object> p3 = new HashMap<>();
        p3.put("objectId", objectId);
        p3.put("status", "PARSED");
        p3.put("summary", "[Mock] 这是与当前作业直接相关的上传文件，适合先提取格式要求并理解题目结构。");
        p3.put("suggestedQuestions", List.of(
                "帮我提取这个文件里的格式要求",
                "这个文件和当前作业的关系是什么",
                "基于这个文件我应该先做什么"));
        p3.put("primaryArtifactUid", artifactUid);
        p3.put("artifactUids", List.of(artifactUid));
        p3.put("meta", Map.of("page_count", 3, "char_count", 1024));
        scheduleEvent(cmd, VerlaAgentEventType.ATTACHMENT_PARSED, p3, 900);
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

    private static Boolean boolField(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "1", "yes").contains(normalized)) {
                return true;
            }
            if (List.of("false", "0", "no").contains(normalized)) {
                return false;
            }
        }
        return null;
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
