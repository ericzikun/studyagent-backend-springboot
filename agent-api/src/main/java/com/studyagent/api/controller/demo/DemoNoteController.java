package com.studyagent.api.controller.demo;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.note.NoteBlogTtsVO;
import com.studyagent.api.dto.demo.note.NoteConversationSummaryVO;
import com.studyagent.api.dto.demo.note.NoteConversationVO;
import com.studyagent.api.dto.demo.note.NoteDocumentPatchRequest;
import com.studyagent.api.dto.demo.note.NoteDraftRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateResponseVO;
import com.studyagent.api.dto.demo.note.NoteProposalCommitRequest;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.application.demo.NoteAccessService;
import com.studyagent.service.application.demo.note.NoteBlogTtsService;
import com.studyagent.service.application.demo.NoteVerlaCommandDispatcher;
import com.studyagent.service.application.demo.dto.NoteTurnContext;
import com.studyagent.service.application.verla.VerlaEditorPdfExportService;
import com.studyagent.service.application.demo.dto.NoteTurnPreparation;
import com.studyagent.service.domain.demo.note.NoteConversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

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
    private final NoteAccessService noteAccessService;
    private final NoteVerlaCommandDispatcher commandDispatcher;
    private final VerlaEditorPdfExportService pdfExportService;
    private final NoteBlogTtsService blogTtsService;

    /** 博客语音合成：按自然段切成可拖动定位的音频片段；重复请求命中缓存。 */
    @PostMapping("/conversations/{id}/blog/tts")
    public Result<NoteBlogTtsVO> blogTts(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id) {
        NoteConversation conversation = service.getOwned(clerkUserId, id);
        String blogMd = conversation.getBlogMd();
        if (blogMd == null || blogMd.isBlank()) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), "博客尚未生成");
        }
        try {
            NoteBlogTtsService.Payload payload = blogTtsService.synthesize(id, blogMd);
            return Result.success(NoteBlogTtsVO.builder()
                    .segments(payload.segments().stream()
                            .map(segment -> NoteBlogTtsVO.Segment.builder()
                                    .index(segment.index())
                                    .paragraphIndex(segment.paragraphIndex())
                                    .text(segment.text())
                                    .url(segment.url())
                                    .durationMs(segment.durationMs())
                                    .build())
                            .toList())
                    .totalDurationMs(payload.totalDurationMs())
                    .voice(payload.voice())
                    .fullUrl(payload.fullUrl())
                    .build());
        } catch (NoteBlogTtsService.TtsUnavailable e) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.INTERNAL_ERROR.getCode(), e.getMessage());
        }
    }

    /** 签名音频流：audio 标签无法带 Clerk 头，鉴权由 exp + HMAC 签名承担。 */
    @GetMapping("/blog-audio/{conversationId}/{fileName}")
    public ResponseEntity<byte[]> blogAudio(
            @PathVariable Long conversationId,
            @PathVariable String fileName,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (!blogTtsService.verify(conversationId, fileName, exp, sig)) {
            return ResponseEntity.status(403).build();
        }
        Path file = blogTtsService.resolveAudio(conversationId, fileName);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] data = Files.readAllBytes(file);
            // 单轨 WAV 有几 MB：支持 Range，浏览器拖动进度时不用整文件重下
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring("bytes=".length()).split("-", 2);
                int total = data.length;
                int start = parts.length > 0 && !parts[0].isBlank()
                        ? Integer.parseInt(parts[0].trim()) : 0;
                int end = parts.length > 1 && !parts[1].isBlank()
                        ? Integer.parseInt(parts[1].trim()) : total - 1;
                start = Math.max(0, Math.min(start, total - 1));
                end = Math.max(start, Math.min(end, total - 1));
                int length = end - start + 1;
                byte[] slice = java.util.Arrays.copyOfRange(data, start, end + 1);
                return ResponseEntity.status(206)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONTENT_RANGE,
                                "bytes " + start + "-" + end + "/" + total)
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                        .contentType(MediaType.parseMediaType("audio/wav"))
                        .contentLength(length)
                        .body(slice);
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .contentLength(data.length)
                    .body(data);
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 进入页面时的会话分配：复用最近的草稿，没有才新建。 */
    @PostMapping("/conversations/draft")
    public Result<NoteConversationVO> draft(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestBody(required = false) NoteDraftRequest req) {
        return Result.success(NoteConversationVO.from(
                service.getOrCreateDraft(clerkUserId, req == null ? null : req.getOutputLanguage())));
    }

    /**
     * 当前用户的历史笔记（左侧历史栏）：按最近活动倒序，只含已提交过素材的记录。
     * <p>返回精简 VO（不含笔记正文），正文按需由快照端点取。
     */
    @GetMapping("/conversations")
    public Result<List<NoteConversationSummaryVO>> listConversations(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(service.listProcessedConversations(clerkUserId, limit).stream()
                .map(NoteConversationSummaryVO::from)
                .toList());
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
        // 对话栏靠 messages 重建；正文靠 noteMd，版本靠 noteVersion（编辑器乐观锁基线）；
        // 待审提案也一并带出，刷新页面仍能继续审阅
        return Result.success(NoteConversationVO.from(
                conv, service.listMessages(conv.getId()), service.parsePendingProposal(conv)));
    }

    /**
     * 笔记导出 PDF：笔记没有 artifact/editor-content 行，不能走作业链路那条
     * {@code artifacts/{uid}/exports/pdf}；这里直接把前端编辑器当前的 TipTap JSON
     * 交给同一个 PDF 渲染服务（Gotenberg），保证两边导出样式一致。
     */
    @PostMapping("/conversations/{id}/exports/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        NoteConversation conv = service.getOwned(
                clerkUserId, service.resolveConversationId(clerkUserId, id));
        Object doc = body == null ? null : body.get("doc");
        if (!(doc instanceof Map<?, ?>)) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(),
                    "doc (TipTap JSON) is required");
        }
        String title = body.get("title") instanceof String s && !s.isBlank()
                ? s.trim()
                : (conv.getTitle() == null || conv.getTitle().isBlank() ? "Note" : conv.getTitle());
        byte[] pdf = pdfExportService.renderTiptapToPdf(title, doc);
        String filename = title.replaceAll("[/\\\\?%*:|\"<>]", "_") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(pdf);
    }

    /**
     * 右侧编辑器保存笔记（手动/防抖自动保存共用）。
     * <p>带 {@code baseVersion} 时是乐观锁：版本不匹配返回参数错误，前端重新拉快照后再保存。
     */
    @PatchMapping("/conversations/{id}")
    public Result<NoteConversationVO> saveNote(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody NoteDocumentPatchRequest req) {
        if (req == null) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), "request body is required");
        }
        NoteConversation conv = service.saveUserNote(clerkUserId, id, req.getContentMd(), req.getBaseVersion());
        return Result.success(NoteConversationVO.from(conv));
    }

    /**
     * 提交待审提案：用户在编辑器里逐块接受/忽略后一次性提交。
     * <p>只应用 accepted 的块；一个都没接受就只清空提案，正文与版本都不动。
     * <p>不是流式端点：写库后直接返回最新会话（正文 + 版本），前端据此替换编辑器内容。
     */
    @PostMapping("/conversations/{id}/proposal/commit")
    public Result<NoteConversationVO> commitProposal(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody NoteProposalCommitRequest req) {
        if (req == null) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), "request body is required");
        }
        NoteConversation conv = service.commitProposal(
                clerkUserId, id, req.getProposalId(), req.getBaseVersion(), toDecisions(req));
        return Result.success(NoteConversationVO.from(conv));
    }

    private static List<Map<String, Object>> toDecisions(NoteProposalCommitRequest req) {
        if (req.getDecisions() == null) {
            return List.of();
        }
        return req.getDecisions().stream()
                .filter(decision -> decision != null)
                .map(decision -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("hunkId", decision.getHunkId());
                    row.put("status", decision.getStatus());
                    return row;
                })
                .toList();
    }

    /**
     * 知识图单图重试：额度不足时返回 INSUFFICIENT_QUOTA，前端打开会员升级弹窗。
     * <p>只重跑图片，不重写笔记、不追加聊天消息。
     */
    @PostMapping("/conversations/{id}/infographic/retry")
    public Result<NoteGenerateResponseVO> retryInfographic(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        noteAccessService.assertCanUseNotes(clerkUserId);
        NoteConversation conv = service.getOwned(
                clerkUserId, service.resolveConversationId(clerkUserId, id));
        Object rawRequestId = body == null ? null : body.get("requestId");
        String requestId = rawRequestId == null ? null : String.valueOf(rawRequestId);
        return Result.success(NoteGenerateResponseVO.from(
                commandDispatcher.dispatchInfographicRetry(conv, requestId)));
    }

    /**
     * 按功能补生成：用户在上传时不生成某一项，之后仍可在对应 tab 单独生成。
     * <p>只补该项，不重写笔记、不追加聊天消息；infographic 复用单图重试的额度逻辑。
     */
    @PostMapping("/conversations/{id}/extras/generate")
    public Result<NoteGenerateResponseVO> generateExtra(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        noteAccessService.assertCanUseNotes(clerkUserId);
        NoteConversation conv = service.getOwned(
                clerkUserId, service.resolveConversationId(clerkUserId, id));
        String feature = body == null || body.get("feature") == null
                ? null : String.valueOf(body.get("feature"));
        String requestId = body == null || body.get("requestId") == null
                ? null : String.valueOf(body.get("requestId"));
        return Result.success(NoteGenerateResponseVO.from(
                commandDispatcher.dispatchExtraGeneration(conv, feature, requestId)));
    }

    /**
     * 派发一轮笔记回合。
     * <p>首轮（带 {@code objectId}/{@code text}）：落素材 + 把 query 落成第 1 条用户消息 →
     * 解析 → 归纳 → 写整篇笔记。<br>
     * 后续轮（只有 {@code message}）：落用户消息 + 带上编辑器基线（{@code documentContentMd} /
     * {@code documentBaseVersion}）与会话历史，agent 自行判断要不要改文档。
     */
    @PostMapping("/conversations/{id}/generate")
    public Result<NoteGenerateResponseVO> generate(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long id,
            @RequestBody NoteGenerateRequest req) {
        if (req == null) {
            throw new com.studyagent.common.exception.BusinessException(
                    com.studyagent.common.api.ApiCode.PARAM_ERROR.getCode(), "request body is required");
        }
        noteAccessService.assertCanUseNotes(clerkUserId);
        boolean firstTurn = DemoNoteService.isFirstTurnInput(req.getSourceType(), req.getObjectId(), req.getText());

        NoteTurnPreparation prepared = firstTurn
                ? service.prepareFirstTurn(clerkUserId, id, req.getSourceType(), req.getObjectId(), req.getText(),
                        req.getFilename(), req.getCourseName(), req.getLearningGoal(),
                        req.getOutputLanguage(), req.getMessage(), req.getInfographicStyle(), req.getFeatures())
                : service.prepareFollowUpTurn(clerkUserId, id, req.getMessage());

        NoteTurnContext turn = NoteTurnContext.builder()
                .firstTurn(firstTurn)
                .message(req.getMessage())
                .outputLanguage(req.getOutputLanguage())
                // 首轮的文档基线恒为空：会话刚被重置，不存在可参考的编辑器基线
                .documentContentMd(firstTurn ? "" : req.getDocumentContentMd())
                .documentBaseVersion(firstTurn ? 0L : req.getDocumentBaseVersion())
                .history(prepared.getHistory())
                // 引用历史笔记：归属校验 + 双预算裁剪都在 service 里做，前端只传 id
                .referencedNotes(service.resolveReferencedNotes(clerkUserId, id, req.getReferencedNoteIds()))
                .webSearch(Boolean.TRUE.equals(req.getWebSearch()))
                .features(req.getFeatures())
                .build();

        return Result.success(NoteGenerateResponseVO.from(
                commandDispatcher.dispatch(prepared.getConversation(), turn)));
    }
}
