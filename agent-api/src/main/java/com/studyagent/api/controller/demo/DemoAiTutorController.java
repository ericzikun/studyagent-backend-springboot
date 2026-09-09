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
import com.studyagent.infra.mq.aitutor.DemoAiTutorCommandDispatcher;
import com.studyagent.service.domain.demo.aitutor.port.DemoAiTutorStreamPublisher;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Tutor（学术论文写作 Copilot）demo 控制器 —— /v1/demo/ai-tutor/*
 * <p>鉴权复用 AuthInterceptor（clerkUserId）。chat 仅走 verla_agent(MQ) python 主循环：Java 派发
 * cmd.aitutor.chat，AITUTOR_* 事件由 DemoAiTutorEventConsumer 桥接回 SSE（不提供 mock 内容）。</p>
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
    private final DemoAiTutorCommandDispatcher commandDispatcher;
    private final DemoAiTutorStreamPublisher streamPublisher;



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
        return Result.success(service.createConversation(clerkUserId, query.trim(), normalizePaperMeta(req == null ? null : req.getPaperMeta())));
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
        return Result.success(service.updatePaperMeta(clerkUserId, id, normalizePaperMeta(req == null ? null : req.getPaperMeta())));
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

    // ============ chat（SSE · 仅 python 主循环，不提供 mock） ============

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

        // 唯一模式：派发 cmd.aitutor.chat 给 verla_agent；AITUTOR_* 事件经 DemoAiTutorEventConsumer
        // 桥接回本 emitter（TURN_COMPLETED 落库并 [DONE]）。python 不可达/无响应时明确报错结束，不吐 mock 内容。
        streamPublisher.register(id, emitter);
        boolean dispatched = commandDispatcher.dispatch(
                clerkUserId, conv, message.trim(), service.getDocumentForConversation(id));
        if (!dispatched) {
            log.warn("[AI-Tutor] python dispatch failed: convId={}", id);
            streamPublisher.markFallback(id);
            sendEvent(emitter, closed, "error", Map.of("content", "AI 服务暂不可用（python 派发失败），请稍后重试"));
            complete(emitter, closed);
            return emitter;
        }
        heartbeatScheduler.schedule(() -> {
            if (!closed.get() && !streamPublisher.hasActivity(id)) {
                log.warn("[AI-Tutor] no python activity within 15s: convId={}", id);
                streamPublisher.markFallback(id);
                sendEvent(emitter, closed, "error", Map.of("content", "AI 主循环未响应（请确认 verla-agent 已就绪并消费 cmd.aitutor.chat）"));
                complete(emitter, closed);
            }
        }, 15, TimeUnit.SECONDS);
        return emitter;
    }

    /** paperMeta 入参归一化：对象 -> JSON 字符串；已是字符串则原样；null 透传。 */
    private String normalizePaperMeta(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "paperMeta 参数非法: " + ex.getMessage());
        }
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
