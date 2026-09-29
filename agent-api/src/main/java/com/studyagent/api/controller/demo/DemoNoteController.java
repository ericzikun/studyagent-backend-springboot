package com.studyagent.api.controller.demo;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.note.NoteConversationVO;
import com.studyagent.api.dto.demo.note.NoteDraftRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateResponseVO;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.application.demo.NoteVerlaCommandDispatcher;
import com.studyagent.service.domain.demo.note.NoteConversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记生成 demo 控制器 —— {@code /v1/demo/note/*}。
 * <p>鉴权复用 AuthInterceptor（clerkUserId）。
 * <p>generate 不是流式端点：只把 {@code cmd.note.generate} 写入事务性 outbox 后立即返回派发回执；
 * {@code NOTE_*} 事件由主线通道回流（verla_event_inbox → SSE），因此天然获得保序、幂等、
 * Last-Event-ID 续传与多标签页广播。
 */
@Slf4j
@RestController
@RequestMapping("/v1/demo/note")
@RequiredArgsConstructor
public class DemoNoteController {

    private final DemoNoteService service;
    private final NoteVerlaCommandDispatcher commandDispatcher;

    /** 进入页面时的会话分配：复用最近的草稿，没有才新建。 */
    @PostMapping("/conversations/draft")
    public Result<NoteConversationVO> draft(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) NoteDraftRequest req) {
        return Result.success(NoteConversationVO.from(
                service.getOrCreateDraft(clerkUserId, req == null ? null : req.getOutputLanguage())));
    }

    /**
     * 会话快照：{@code id} 同时接受 URL 里的主线 public id（{@code vc_xxx}）与 demo 主键数字。
     * <p>刷新页面时前端靠它拿回已生成的 {@code noteMd}。
     */
    @GetMapping("/conversations/{id}")
    public Result<NoteConversationVO> snapshot(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String id) {
        NoteConversation conv = service.getOwned(clerkUserId, service.resolveConversationId(clerkUserId, id));
        return Result.success(NoteConversationVO.from(conv));
    }

    @PostMapping("/conversations/{id}/generate")
    public Result<NoteGenerateResponseVO> generate(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody NoteGenerateRequest req) {
        if (req == null) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), "request body is required");
        }
        NoteConversation conv = service.prepareGeneration(
                clerkUserId, id, req.getSourceType(), req.getObjectId(), req.getText(), req.getFilename());
        return Result.success(NoteGenerateResponseVO.from(
                commandDispatcher.dispatch(conv, req.getOutputLanguage())));
    }
}
