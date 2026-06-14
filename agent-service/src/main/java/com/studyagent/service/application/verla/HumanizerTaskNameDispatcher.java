package com.studyagent.service.application.verla;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.envelope.VerlaCommandEnvelope;
import com.studyagent.common.verla.envelope.VerlaConversationRef;
import com.studyagent.common.verla.envelope.VerlaProducerInfo;
import com.studyagent.common.verla.envelope.VerlaSessionRef;
import com.studyagent.common.verla.envelope.VerlaTurnRef;
import com.studyagent.common.verla.util.VerlaCorrelationId;
import com.studyagent.service.application.MqOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 为独立的 {@code /v1/humanizer} 检测/改写任务下发会话标题命令。
 * <p>
 * 这些任务（{@code humanizer_tasks} 表）不走 Verla 会话/turn 机制，但同样希望复用
 * Python 的 {@code ConversationTitleService} 生成标题。做法：下发标准的
 * {@code cmd.plan.task_name} 命令，并在 payload 内携带 {@code humanizerTaskId} 与
 * {@code scope=HUMANIZER} 标记；Python 原样回显到 {@code PLAN_TASK_NAME_RESOLVED} 事件，
 * Java 端 {@code VerlaConversationTitleEventHandler} 识别该标记后回写
 * {@code humanizer_tasks.task_name}（而非 verla_conversations.title）。
 * <p>
 * 关键点：合成的 conversation/turn/session id 落在 {@link #HUMANIZER_ID_NAMESPACE}
 * 高位命名空间，避开真实 Verla 自增 id，防止与真实会话在共享的
 * {@code verla_event_inbox} / {@code verla_event_cursor} 表内撞键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanizerTaskNameDispatcher {

    public static final String SCOPE_HUMANIZER = "HUMANIZER";

    private static final String PRODUCER_SERVICE = "java-agent-service";
    private static final String INSTANCE_ID = resolveHostname();
    private static final String DEFAULT_COMMAND_EXCHANGE = "studyagent.command";

    /**
     * Humanizer 独立任务合成 id 的命名空间起点（9e15）。real verla 自增 id 短期内
     * 远不会到达此区间，从而保证共享事件表内不撞键；与 Long 上限（约 9.2e18）保持安全距离。
     */
    private static final long HUMANIZER_ID_NAMESPACE = 9_000_000_000_000_000L;

    /** 标题只需输入正文开头部分，截断以控制 prompt 体积。 */
    private static final int MAX_TITLE_SOURCE_CHARS = 4000;

    @Value("${verla.mq.command-exchange:" + DEFAULT_COMMAND_EXCHANGE + "}")
    private String commandExchange;

    private final MqOutboxService mqOutboxService;

    /**
     * best-effort 下发标题生成命令；任何异常只记日志，绝不影响任务提交主流程。
     */
    public void dispatch(Long humanizerTaskId, String clerkUserId, String text) {
        if (humanizerTaskId == null) {
            return;
        }
        try {
            long synthetic = HUMANIZER_ID_NAMESPACE + humanizerTaskId;
            Map<String, Object> payload = new HashMap<>();
            payload.put("userText", truncate(text));
            payload.put("scope", SCOPE_HUMANIZER);
            payload.put("humanizerTaskId", humanizerTaskId);

            VerlaCommandEnvelope env = VerlaCommandEnvelope.builder()
                    .schemaVersion(1)
                    .messageId("cmd-" + UUID.randomUUID())
                    .correlationId(VerlaCorrelationId.of(synthetic, synthetic, synthetic))
                    .orderingKey(VerlaCorrelationId.orderingKey(synthetic))
                    .action(VerlaCommandAction.CMD_PLAN_TASK_NAME.getCode())
                    .timestamp(Instant.now())
                    .producer(VerlaProducerInfo.builder()
                            .service(PRODUCER_SERVICE)
                            .instanceId(INSTANCE_ID)
                            .build())
                    .conversation(VerlaConversationRef.builder()
                            .conversationId(synthetic)
                            .userId(clerkUserId)
                            .build())
                    .turn(VerlaTurnRef.builder()
                            .turnId(synthetic)
                            .build())
                    .session(VerlaSessionRef.builder()
                            .sessionId(synthetic)
                            .kind(VerlaSessionKind.TASK_NAME)
                            .build())
                    .payload(payload)
                    .build();

            mqOutboxService.createVerlaCommand(env, commandExchange,
                    VerlaCommandAction.CMD_PLAN_TASK_NAME.getCode());
            log.info("[Humanizer/taskName] dispatched cmd.plan.task_name humanizerTaskId={} syntheticId={}",
                    humanizerTaskId, synthetic);
        } catch (Exception e) {
            log.warn("[Humanizer/taskName] dispatch failed humanizerTaskId={}: {}",
                    humanizerTaskId, e.getMessage());
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= MAX_TITLE_SOURCE_CHARS ? t : t.substring(0, MAX_TITLE_SOURCE_CHARS);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
