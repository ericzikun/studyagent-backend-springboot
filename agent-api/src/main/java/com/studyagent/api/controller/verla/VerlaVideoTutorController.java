package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.google.gson.Gson;
import com.studyagent.api.dto.verla.request.VideoTutorCloneTurnRequest;
import com.studyagent.api.dto.verla.request.VideoTutorTtsRequest;
import com.studyagent.api.dto.verla.request.VideoTutorTurnRequest;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.SendMessageResponseVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.dto.verla.response.VideoTutorSnapshotVO;
import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.VideoTutorSnapshotService;
import com.studyagent.service.application.verla.clone.CloneLessonStore;
import com.studyagent.service.application.verla.clone.VideoTutorCloneAgentService;
import com.studyagent.service.application.verla.tts.VideoTutorTtsService;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Video Tutor：交互式白板教学（左栏对话 + 右栏白板）。
 * <p>
 * 历史隔离（{@code scene=VIDEO_TUTOR}）：左栏可见、可回看，但不进主对话线程。
 * <p>
 * <b>不做 legacy 会话兼容</b>：这是新产品，历史里不可能存在 legacy 形态的白板会话
 * （作业追问那边要兼容是因为它挂在既有 assignment 会话上）。少一个分支就少一处将来会腐的逻辑。
 * <p>
 * <b>没有 retry 端点</b>：白板一轮的产物是"板上现有什么"，重打同一轮会重复上板，v1 不提供。
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla/conversations")
@RequiredArgsConstructor
public class VerlaVideoTutorController {

    private final VerlaTurnOrchestrator turnOrchestrator;
    private final VerlaConversationService conversationService;
    private final VideoTutorSnapshotService snapshotService;
    private final VerlaMessageRepository messageRepository;
    private final VideoTutorTtsService ttsService;
    private final VideoTutorCloneAgentService cloneAgent;
    private final CloneLessonStore cloneStore;
    private final Set<Long> cloneRunning = ConcurrentHashMap.newKeySet();
    private static final Gson GSON = new Gson();

    /**
     * 发起一轮白板教学。返回本轮 turnId / userMessageId / agentSessionId。
     * <p>
     * {@code message} 可为空（首轮）；同一 conversation 上已有未完成的白板 turn 时
     * 返回 {@code ILLEGAL_STATE}（error data 带 {@code runningTurnId}）。
     */
    @PostMapping("/{cid}/video-tutor/messages")
    public Result<SendMessageResponseVO> sendMessage(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody @Valid VideoTutorTurnRequest req) {
        ensureLogin(clerkUserId);
        // 新前端用 Java 直连 TTS：本轮不要再让 Python 合成一遍（不走 MQ/OSS）。
        // Java 未配置密钥时回落旧链路，避免"关了 Python 又没人合成"。
        boolean javaTts = "java".equalsIgnoreCase(req.getTtsProvider()) && ttsService.isReady();
        if ("java".equalsIgnoreCase(req.getTtsProvider()) && !javaTts) {
            log.warn("[video-tutor/tts] java provider requested but not ready; falling back to backend synthesis");
        }
        boolean backendSound = Boolean.TRUE.equals(req.getSoundEnabled()) && !javaTts;
        return Result.success(SendMessageResponseVO.from(
                turnOrchestrator.startVideoTutorTurn(
                        clerkUserId, cid, req.getMessage(), req.getOutputLanguage(),
                        backendSound,
                        req.getTeachingStyle(),
                        Boolean.TRUE.equals(req.getStyleChanged()))));
    }

    /**
     * 刷新 / 重开恢复：一次拿到消息 + 板子 + SSE 续传游标。
     * <p>
     * artifact 优先，不重放事件（板子的唯一真相是会话级单行 artifact）。
     */
    @GetMapping("/{cid}/video-tutor/snapshot")
    public Result<VideoTutorSnapshotVO> snapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        return Result.success(VideoTutorSnapshotVO.from(snapshotService.getSnapshot(cid)));
    }

    /** 左栏历史向上翻页（快照只给最近 100 条）。 */
    @GetMapping("/{cid}/video-tutor/messages")
    public Result<MessagePageVO> listMessages(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = messageRepository.findVideoTutorByCursor(cid, cursor, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).toList())
                .nextCursor(nextCursor)
                .build());
    }

    @PostMapping("/{cid}/video-tutor/sessions/{sid}/cancel")
    public Result<SendMessageResponseVO> cancelSession(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @VerlaPublicId(VerlaPublicIdType.SESSION) @PathVariable Long sid) {
        ensureLogin(clerkUserId);
        return Result.success(SendMessageResponseVO.from(
                turnOrchestrator.cancelVideoTutorTurn(clerkUserId, cid, sid)));
    }

    /**
     * 按拍朗读：Java 直接把 CosyVoice 的 PCM 流回给浏览器。
     *
     * 前端按 clone 的方式用 WebAudio 边收边排程，所以这里必须是流式响应，不能等整句合成完。
     * 只看会话归属，不落库、不计费、不产生 MQ 消息。
     */
    @PostMapping(value = "/{cid}/video-tutor/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> tts(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody VideoTutorTtsRequest req) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        String text = req.getText() == null ? "" : req.getText().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
        }
        VideoTutorTtsService.Stream stream;
        try {
            stream = ttsService.open(text, req.getSpeed(), req.getTutorId());
        } catch (VideoTutorTtsService.TtsUnavailableException e) {
            log.warn("[video-tutor/tts] unavailable cid={} textChars={} reason={}",
                    cid, text.length(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        StreamingResponseBody body = out -> {
            try {
                stream.writeTo(out);
            } finally {
                stream.close();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("x-audio-format", "pcm")
                .header("x-tts-sample-rate", String.valueOf(ttsService.sampleRate()))
                .header("x-tts-provider", "cosyvoice-java")
                .header("Cache-Control", "no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** clone 模式的刷新恢复：左栏消息 + 全部 TutorCommand。 */
    @GetMapping("/{cid}/video-tutor/clone/snapshot")
    public Result<Map<String, Object>> cloneSnapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        CloneLessonStore.State state = cloneStore.read(cid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", cid);
        data.put("messages", state.messages());
        data.put("events", state.events());
        return Result.success(data);
    }

    /**
     * clone 的一轮：Java 自己跑 agent，按 JSON Lines 把 TutorCommand 流回浏览器。
     * 不写 MQ、不经过 Python。
     */
    @PostMapping(value = "/{cid}/video-tutor/clone/turn", produces = "application/x-ndjson;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> cloneTurn(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestBody VideoTutorCloneTurnRequest req) {
        ensureLogin(clerkUserId);
        VerlaConversation conversation = conversationService.getOwned(clerkUserId, cid);
        if (!cloneAgent.isReady()) {
            log.warn("[video-tutor/clone] agent not ready cid={}", cid);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!cloneRunning.add(cid)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        String message = req.getMessage() == null ? "" : req.getMessage().trim();
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        CloneLessonStore.State before = cloneStore.read(cid);
        List<Map<String, Object>> userMessages = new ArrayList<>();
        if (!message.isEmpty()) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("text", message);
            user.put("at", Instant.now().toString());
            userMessages.add(user);
            cloneStore.save(cid, before.append(userMessages, List.of()));
        }
        String transcript = transcript(before, message);
        VideoTutorCloneAgentService.TurnInput input = new VideoTutorCloneAgentService.TurnInput(
                cid,
                conversation.getTitle() == null || conversation.getTitle().isBlank()
                        ? "白板课" : conversation.getTitle(),
                message,
                req.getLanguage() == null || req.getLanguage().isBlank() ? "zh-CN" : req.getLanguage(),
                req.getTeachingStyle(),
                transcript
        );
        StreamingResponseBody body = out -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            Object lock = new Object();
            try {
                VideoTutorCloneAgentService.TurnResult result = cloneAgent.run(input, line -> {
                    try {
                        synchronized (lock) {
                            writer.write(GSON.toJson(line));
                            writer.newLine();
                            writer.flush();
                        }
                    } catch (java.io.IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                List<Map<String, Object>> messages = new ArrayList<>(userMessages);
                if (!result.assistantText().isBlank()) {
                    Map<String, Object> assistant = new LinkedHashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("text", result.assistantText());
                    assistant.put("at", Instant.now().toString());
                    messages.add(assistant);
                }
                cloneStore.save(cid, before.append(messages, result.commands()));
            } catch (Exception e) {
                log.warn("[video-tutor/clone] turn aborted cid={}: {}", cid, e.getMessage());
                try {
                    synchronized (lock) {
                        writer.write(GSON.toJson(Map.of("_meta", "error",
                                "message", e.getMessage() == null ? "clone turn failed" : e.getMessage())));
                        writer.newLine();
                        writer.flush();
                    }
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            } finally {
                cloneRunning.remove(cid);
                try {
                    writer.flush();
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .header("Cache-Control", "no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private static String transcript(CloneLessonStore.State state, String currentMessage) {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> events = state.events();
        int start = Math.max(0, events.size() - 50);
        for (int i = start; i < events.size(); i += 1) {
            Map<String, Object> event = events.get(i);
            Object nameValue = event.get("commandName");
            if (!(nameValue instanceof String name)) {
                continue;
            }
            switch (name) {
                case "speak" -> lines.add("老师：" + string(event.get("text")));
                case "write_text" -> lines.add("[板书] " + plain(string(event.get("text"))).substring(
                        0, Math.min(80, plain(string(event.get("text"))).length())));
                case "write_latex" -> {
                    String latex = string(event.get("latex"));
                    lines.add("[公式] " + (latex.isBlank() ? string(event.get("text")) : latex));
                }
                case "draw" -> lines.add("[画图] " + string(event.get("kind")) + ": "
                        + string(event.get("prompt")).substring(0,
                        Math.min(80, string(event.get("prompt")).length())));
                default -> {
                    // 其他命令不进口述转录
                }
            }
        }
        for (Map<String, Object> message : state.messages()) {
            if ("user".equals(string(message.get("role")))) {
                lines.add("学生：" + string(message.get("text")));
            }
        }
        if (!currentMessage.isBlank()) {
            lines.add("学生：" + currentMessage);
        }
        if (lines.size() > 36) {
            lines = lines.subList(lines.size() - 36, lines.size());
        }
        return String.join("\n", lines);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String plain(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
