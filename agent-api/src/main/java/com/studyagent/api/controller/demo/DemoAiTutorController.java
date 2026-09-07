package com.studyagent.api.controller.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.aitutor.ChatRequest;
import com.studyagent.api.dto.demo.aitutor.CreateConversationRequest;
import com.studyagent.api.dto.demo.aitutor.DocumentPatchRequest;
import com.studyagent.api.dto.demo.aitutor.EvidenceConfirmRequest;
import com.studyagent.api.dto.demo.aitutor.MaterialRequest;
import com.studyagent.api.dto.demo.aitutor.PaperMetaRequest;
import com.studyagent.service.application.demo.DemoAiTutorService;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.AiTutorEvidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Tutor（学术论文写作 Copilot）demo 控制器 —— /v1/demo/ai-tutor/*
 * <p>鉴权复用 AuthInterceptor（clerkUserId）。M0 阶段 chat 为后端 mock 流（demo.aitutor.chat.mode=mock），
 * 后续接入 verla_agent(MQ) 时切换 mode=python 并由事件投影驱动。</p>
 */
@Slf4j
@RestController
@RequestMapping("/v1/demo/ai-tutor")
@RequiredArgsConstructor
public class DemoAiTutorController {

    private static final long SSE_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final DemoAiTutorService service;
    private final ObjectMapper objectMapper;

    @Value("${demo.aitutor.chat.mode:mock}")
    private String chatMode;

    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "demo-ai-tutor-run");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "demo-ai-tutor-heartbeat");
                t.setDaemon(true);
                return t;
            });

    // ============ 会话 ============

    @PostMapping("/conversations")
    public Result<AiTutorConversation> create(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) CreateConversationRequest req) {
        String query = req == null ? "" : req.getInitialQuery();
        if (query == null || query.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "initialQuery is required");
        }
        return Result.success(service.createConversation(clerkUserId, query.trim(), req.getPaperMeta()));
    }

    @GetMapping("/conversations")
    public Result<List<AiTutorConversation>> list(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(service.listConversations(clerkUserId, limit));
    }

    @GetMapping("/conversations/{id}")
    public Result<Map<String, Object>> snapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id) {
        return Result.success(service.snapshot(clerkUserId, id));
    }

    @PatchMapping("/conversations/{id}")
    public Result<AiTutorConversation> updatePaperMeta(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody PaperMetaRequest req) {
        return Result.success(service.updatePaperMeta(clerkUserId, id, req.getPaperMeta()));
    }

    // ============ 文档 ============

    @PatchMapping("/conversations/{id}/document")
    public Result<AiTutorDocument> saveUserDocument(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody DocumentPatchRequest req) {
        service.getOwned(clerkUserId, id);
        return Result.success(service.saveUserDocument(id, req.getContentMd(), req.getBaseVersion()));
    }

    @PostMapping("/conversations/{id}/document/undo")
    public Result<AiTutorDocument> undo(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id) {
        service.getOwned(clerkUserId, id);
        return Result.success(service.undo(id));
    }

    @PostMapping("/conversations/{id}/document/redo")
    public Result<AiTutorDocument> redo(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id) {
        service.getOwned(clerkUserId, id);
        return Result.success(service.redo(id));
    }

    // ============ 引用证据 ============

    @PostMapping("/conversations/{id}/materials")
    public Result<AiTutorEvidence> addMaterial(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody MaterialRequest req) {
        service.getOwned(clerkUserId, id);
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "content is required");
        }
        return Result.success(service.addUserMaterial(id, req.getTitle(), req.getContent()));
    }

    @PostMapping("/conversations/{id}/evidence/confirm")
    public Result<List<AiTutorEvidence>> confirmEvidences(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody EvidenceConfirmRequest req) {
        service.getOwned(clerkUserId, id);
        return Result.success(service.confirmEvidences(id, req.getEvidenceIds()));
    }

    // ============ chat（SSE） ============

    @PostMapping(path = "/conversations/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody ChatRequest req) {
        String message = req.getMessage();
        if (message == null || message.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "message is required");
        }
        AiTutorConversation conv = service.getOwned(clerkUserId, id);
        service.appendMessage(id, "user", "text", message.trim());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        heartbeatScheduler.scheduleAtFixedRate(
                () -> sendComment(emitter, closed), HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        if ("python".equalsIgnoreCase(chatMode)) {
            // M1：切到 verla_agent(MQ) 后由事件投影驱动；此处先回错误事件
            executor.submit(() -> {
                sendEvent(emitter, closed, "error", Map.of("content", "python mode 尚未接入（M1）"));
                complete(emitter, closed);
            });
            return emitter;
        }

        executor.submit(() -> runMockTurn(emitter, closed, conv, message.trim()));
        return emitter;
    }

    /** M0 mock 回合：派活 writer → 生成论文骨架 → 落文档(ai 版本) → 自然语言承接。 */
    private void runMockTurn(SseEmitter emitter, AtomicBoolean closed, AiTutorConversation conv, String message) {
        try {
            String title = conv.getTitle() == null ? "未命名论文" : conv.getTitle();
            sendEvent(emitter, closed, "message", Map.of("type", "agent_start", "agent", "planner", "goal", "为「" + title + "」生成论文结构"));
            Thread.sleep(120);
            String skeleton = buildSkeleton(title);
            sendEvent(emitter, closed, "artifact", Map.of("type", "begin", "versionNo", 1));
            sendEvent(emitter, closed, "artifact", Map.of("type", "delta", "op", "replace_all", "content", skeleton));
            service.saveAiUpdate(conv.getId(), skeleton);
            sendEvent(emitter, closed, "artifact", Map.of("type", "commit", "versionNo", 1));
            sendEvent(emitter, closed, "message", Map.of("type", "agent_end", "agent", "planner", "summary", "已生成大纲骨架"));

            sendEvent(emitter, closed, "message", Map.of("type", "agent_start", "agent", "mentor"));
            String narration = "好的，我已经为「" + title + "」生成论文大纲骨架，并同步到右侧文档 v1。"
                    + "接下来你可以：让我展开某一节（例如「把引言写出来」）；"
                    + "粘贴文献材料，我会先给出引用来源确认卡；"
                    + "或直接修改右侧文档，让我基于你的改动继续。";
            for (String sentence : narration.split("(?<=。)")) {
                if (sentence.isBlank()) {
                    continue;
                }
                sendEvent(emitter, closed, "message", Map.of("type", "chunk", "content", sentence));
                Thread.sleep(80);
            }
            sendEvent(emitter, closed, "message", Map.of("type", "agent_end", "agent", "mentor"));
            service.appendMessage(conv.getId(), "assistant", "text", narration.replace("\\n", "\n"));
            sendEvent(emitter, closed, "auto_saved", Map.of("savedAt", java.time.LocalDateTime.now().toString()));
        } catch (Exception ex) {
            log.error("[AI-Tutor] mock turn failed: {}", ex.getMessage());
            sendEvent(emitter, closed, "error", Map.of("content", ex.getMessage()));
        } finally {
            complete(emitter, closed);
        }
    }

    private String buildSkeleton(String title) {
        return "# " + title + "\n\n"
                + "> 摘要（待补充）\n\n"
                + "## 1 引言\n\n本节交代研究背景、问题与目标。（待展开）\n\n"
                + "## 2 相关工作\n\n梳理已有工作与本课题的关系。（待文献引用）\n\n"
                + "## 3 方法\n\n描述核心方法/框架与设计思路。（待展开）\n\n"
                + "## 4 讨论\n\n对结果与局限展开讨论。（待展开）\n\n"
                + "## 5 结论与展望\n\n总结贡献并展望后续工作。（待展开）\n\n"
                + "## 参考文献\n\n引用经确认后自动生成。";
    }

    private void sendEvent(SseEmitter emitter, AtomicBoolean closed, String name, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException ex) {
            closed.set(true);
        }
    }

    private void sendComment(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ex) {
            closed.set(true);
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException | IllegalStateException ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
