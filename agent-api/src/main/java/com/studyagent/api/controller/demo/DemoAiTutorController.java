package com.studyagent.api.controller.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.aitutor.AiTutorChatResponseVO;
import com.studyagent.api.dto.demo.aitutor.AiTutorConversationVO;
import com.studyagent.api.dto.demo.aitutor.ChatRequest;
import com.studyagent.api.dto.demo.aitutor.CreateConversationRequest;
import com.studyagent.api.dto.demo.aitutor.DocumentPatchRequest;
import com.studyagent.api.dto.demo.aitutor.EvidenceConfirmRequest;
import com.studyagent.api.dto.demo.aitutor.MaterialRequest;
import com.studyagent.api.dto.demo.aitutor.SessionContextRequest;
import com.studyagent.service.application.demo.AiTutorVerlaCommandDispatcher;
import com.studyagent.service.application.demo.DemoAiTutorService;
import com.studyagent.service.domain.demo.aitutor.AiTutorConversation;
import com.studyagent.service.domain.demo.aitutor.AiTutorDocument;
import com.studyagent.service.domain.demo.aitutor.AiTutorEvidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI Tutor（学术论文写作 Copilot）demo 控制器 —— /v1/demo/ai-tutor/*
 * <p>鉴权复用 AuthInterceptor（clerkUserId）。
 * <p>chat 不是流式端点：只把 {@code cmd.aitutor.chat} 写入事务性 outbox 后立即返回派发回执；
 * {@code AITUTOR_*} 事件由主线通道回流（verla_event_inbox → AiTutorEventHandler 投影 →
 * {@code GET /v1/verla/conversations/{vc_xxx}/events} 的 SSE），因此天然获得保序、幂等、
 * Last-Event-ID 续传与多标签页广播，demo 不再自建 SSE 桥接与看门狗。
 */
@Slf4j
@RestController
@RequestMapping("/v1/demo/ai-tutor")
@RequiredArgsConstructor
public class DemoAiTutorController {

    private final DemoAiTutorService service;
    private final ObjectMapper objectMapper;
    private final AiTutorVerlaCommandDispatcher commandDispatcher;

    // ============ 会话 ============

    @PostMapping("/conversations")
    public Result<AiTutorConversationVO> create(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) CreateConversationRequest req) {
        String query = req == null ? "" : req.getInitialQuery();
        if (query == null || query.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "initialQuery is required");
        }
        AiTutorConversation created = service.createConversation(
                clerkUserId, query.trim(), normalizeSessionContext(req == null ? null : req.getSessionContext()));
        return Result.success(AiTutorConversationVO.from(created));
    }

    /**
     * 进入 AI Tutor 页面时的会话分配：复用最近的未使用草稿，没有才新建。
     * <p>前端拿到 {@code verlaConversationId(vc_xxx)} 后替换 URL，刷新/分享都以该 public id 为准。
     */
    @PostMapping("/conversations/draft")
    public Result<AiTutorConversationVO> draft(@RequestAttribute("clerkUserId") String clerkUserId) {
        return Result.success(AiTutorConversationVO.from(service.getOrCreateDraftConversation(clerkUserId)));
    }

    @GetMapping("/conversations")
    public Result<List<AiTutorConversationVO>> list(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(service.listConversations(clerkUserId, limit).stream()
                .map(AiTutorConversationVO::from)
                .toList());
    }

    /**
     * 会话快照：{@code id} 同时接受 URL 里的主线 public id（{@code vc_xxx}）与迁移期 demo 主键数字。
     * <p>这是唯一按 public id 解析的入口，响应里带 demo 主键，其余端点继续按 demo 主键工作。
     */
    @GetMapping("/conversations/{id}")
    public Result<Map<String, Object>> snapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String id) {
        Map<String, Object> snapshot = service.snapshot(clerkUserId, service.resolveConversationId(clerkUserId, id));
        // 会话项换成 VO：刷新页面后前端要靠 verlaConversationId(vc_xxx) 重开 SSE 通道。
        if (snapshot.get("conversation") instanceof AiTutorConversation conv) {
            snapshot.put("conversation", AiTutorConversationVO.from(conv));
        }
        return Result.success(snapshot);
    }

    @PatchMapping("/conversations/{id}")
    public Result<AiTutorConversationVO> updateSessionContext(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody SessionContextRequest req) {
        AiTutorConversation updated = service.updateSessionContext(
                clerkUserId, id, normalizeSessionContext(req == null ? null : req.getSessionContext()));
        return Result.success(AiTutorConversationVO.from(updated));
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

    // ============ chat（命令派发 · 事件走主线 SSE） ============

    @PostMapping("/conversations/{id}/chat")
    public Result<AiTutorChatResponseVO> chat(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody ChatRequest req) {
        String message = req.getMessage();
        if (message == null || message.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "message is required");
        }
        AiTutorConversation conv = service.ensureVerlaLink(clerkUserId, id);
        if (service.listMessages(id).isEmpty()) {
            // 草稿会话的标题/初始目标/会话上下文都随首条消息确定，且必须在派发前落库
            conv = service.applyFirstMessage(
                    clerkUserId, conv, message.trim(), normalizeSessionContext(req.getSessionContext()));
        }
        service.appendMessage(id, "user", "text", message.trim());
        return Result.success(AiTutorChatResponseVO.from(
                commandDispatcher.dispatch(conv, service.getDocumentForConversation(id), message.trim())));
    }

    /** sessionContext 入参归一化：对象 -> JSON 字符串；已是字符串则原样；null 透传。 */
    private String normalizeSessionContext(com.fasterxml.jackson.databind.JsonNode node) {
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
                    com.studyagent.common.api.ApiCode.PARAM_ERROR, "sessionContext 参数非法: " + ex.getMessage());
        }
    }
}
