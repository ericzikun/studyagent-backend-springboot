package com.studyagent.service.application.verla.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.verla.enums.VerlaAgentEventType;
import com.studyagent.common.verla.enums.VerlaArtifactStatus;
import com.studyagent.common.verla.envelope.VerlaEventEnvelope;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaEventInbox;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Video Tutor 白板事件 handler。
 * <p>
 * 只注册 5 个事件。{@code VIDEO_TUTOR_BOARD_OPS} 与 {@code VIDEO_TUTOR_CHAT_STREAM_CHUNK}
 * 故意不注册 —— 但要说清它<b>不是</b>"不进 inbox"：SSE 正是由 {@code VerlaInboxService.drainAndDispatch}
 * 驱动的，每一行成功处理的事件都会推给前端，所以渲染帧照样落一行 inbox、照样参与 seq 保序
 * （这正是前端能按序收到 step 帧的原因）。不注册的含义只是<b>不做投影副作用</b>。
 * <p>
 * 漏注册真投影 handler 的症状是"事件被静默 processed、只有投影缺失"，
 * 所以 {@link #supportedTypes()} 有一条内容断言的单测兜底。
 * <p>
 * 白板状态落在 {@code verla_artifacts} 的<b>会话级单行</b>（{@code artifact_uid = board_{conversationId}}），
 * 因此刷新恢复只读这一行，不必重放整轮事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTutorEventHandler implements VerlaEventHandler {

    public static final String BOARD_ARTIFACT_KIND = "video_tutor_board";
    public static final String SCENE_VIDEO_TUTOR = "VIDEO_TUTOR";

    private static final Set<VerlaAgentEventType> SUPPORTED = EnumSet.of(
            VerlaAgentEventType.VIDEO_TUTOR_STARTED,
            VerlaAgentEventType.VIDEO_TUTOR_BOARD_COMMIT,
            VerlaAgentEventType.VIDEO_TUTOR_TURN_COMPLETED,
            VerlaAgentEventType.VIDEO_TUTOR_FAILED,
            VerlaAgentEventType.VIDEO_TUTOR_CANCELLED);

    private final VerlaTurnOrchestrator orchestrator;
    private final VerlaArtifactRepository artifactRepository;
    private final VerlaMessageRepository messageRepository;
    private final VerlaConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<VerlaAgentEventType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(VerlaEventInbox row, VerlaEventEnvelope env) {
        VerlaAgentEventType type = VerlaAgentEventType.valueOf(row.getEventType());
        Map<String, Object> payload = env == null || env.getPayload() == null
                ? Map.of() : env.getPayload();

        switch (type) {
            case VIDEO_TUTOR_STARTED -> orchestrator.onAgentStarted(row.getSessionId());
            case VIDEO_TUTOR_FAILED -> orchestrator.onAgentFailed(row.getSessionId(), payload);
            case VIDEO_TUTOR_CANCELLED -> orchestrator.onAgentCancelled(row.getSessionId());
            case VIDEO_TUTOR_BOARD_COMMIT -> upsertBoard(row, payload);
            case VIDEO_TUTOR_TURN_COMPLETED -> {
                // 板子已由前一行 BOARD_COMMIT 落库；这里不再 upsert —— 终态 payload 里没有 board 键
                orchestrator.onAgentCompleted(row.getSessionId(), payload);
                insertAssistantMessage(row, payload);
            }
            default -> log.warn("[Verla/videoTutor] unexpected event {}", type);
        }
    }

    /**
     * 白板树 → 会话级单行 artifact。
     * <p>
     * <b>版本号由 Java 决定</b>：Python 回显的 {@code boardVersion} 只拿来对账打日志，
     * 真正写入用 {@code 现存 version + 1}。原因是 {@code upsertByUid} 的策略是
     * "高 version 覆盖低 version、低 version 直接丢弃"，若照抄回显值，
     * 一旦 Python 看到的 boardVersion 落后，这一轮的板子会被当成旧消息<b>静默丢掉</b>。
     */
    private void upsertBoard(VerlaEventInbox row, Map<String, Object> payload) {
        if (!payload.containsKey("board")) {
            log.warn("[Verla/videoTutor] BOARD_COMMIT without board, sessionId={} seq={}",
                    row.getSessionId(), row.getEventSeq());
            return;
        }
        String uid = boardArtifactUid(row.getConversationId());
        String body = toJson(payload.get("board"));
        int nextVersion = nextBoardVersion(uid);

        VerlaArtifact saved = artifactRepository.upsertByUid(VerlaArtifact.builder()
                .artifactUid(uid)
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .sessionId(row.getSessionId())
                .kind(BOARD_ARTIFACT_KIND)
                .mime("application/json")
                .summary("Video Tutor 白板")
                .bodyOrRef(body)
                .status(VerlaArtifactStatus.READY.name())
                .sizeBytes(body == null ? null : (long) body.getBytes(StandardCharsets.UTF_8).length)
                .version(nextVersion)
                .metaJson(toJson(boardMeta(payload)))
                .build());

        if (saved == null || !Objects.equals(saved.getVersion(), nextVersion)) {
            // 说明这一帧被当成旧版本丢弃了 —— 不会报错，只有板子不更新，必须留下痕迹
            log.warn("[Verla/videoTutor] board commit discarded uid={} wanted={} got={} echoed={}",
                    uid, nextVersion, saved == null ? null : saved.getVersion(),
                    payload.get("boardVersion"));
        } else {
            log.info("[Verla/videoTutor] board upsert uid={} version={} echoed={} bytes={}",
                    uid, nextVersion, payload.get("boardVersion"), body == null ? 0 : body.length());
        }

        conversationRepository.incrementVersion(row.getConversationId());
    }

    private int nextBoardVersion(String uid) {
        VerlaArtifact existing = artifactRepository.findByUid(uid);
        Integer current = existing == null ? null : existing.getVersion();
        return current == null ? 1 : current + 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> boardMeta(Map<String, Object> payload) {
        Object meta = payload.get("meta");
        return meta instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * 终态时把老师这轮的问句落成 assistant 消息。
     * <p>
     * 幂等：按 {@code (turnId, assistant, scene)} 查重，MQ 重投不会写第二条。
     */
    private void insertAssistantMessage(VerlaEventInbox row, Map<String, Object> payload) {
        String text = str(payload.get("finalText"));
        if (text == null || text.isBlank()) {
            log.info("[Verla/videoTutor] TURN_COMPLETED with empty finalText, turnId={}",
                    row.getTurnId());
            return;
        }
        if (messageRepository.findByTurnRoleScene(row.getTurnId(), "assistant", SCENE_VIDEO_TUTOR) != null) {
            return;
        }
        messageRepository.save(VerlaMessage.builder()
                .conversationId(row.getConversationId())
                .turnId(row.getTurnId())
                .role("assistant")
                .sourceSessionId(row.getSessionId())
                .textContent(text)
                .scene(SCENE_VIDEO_TUTOR)
                .metaJson(toJson(Map.of("scene", SCENE_VIDEO_TUTOR)))
                .createdAt(LocalDateTime.now())
                .build());
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[Verla/videoTutor] serialize failed: {}", e.getMessage());
            return null;
        }
    }

    /** 供 snapshot service 复用：会话级单行 uid；{@code uk_artifact_uid} 因此天然幂等。 */
    public static String boardArtifactUid(Long conversationId) {
        return "board_" + conversationId;
    }
}
