package com.studyagent.api.controller.demo;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.demo.note.NoteConversationSummaryVO;
import com.studyagent.api.dto.demo.note.NoteConversationVO;
import com.studyagent.api.dto.demo.note.NoteDocumentPatchRequest;
import com.studyagent.api.dto.demo.note.NoteDraftRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateRequest;
import com.studyagent.api.dto.demo.note.NoteGenerateResponseVO;
import com.studyagent.api.dto.demo.note.NoteProposalCommitRequest;
import com.studyagent.service.application.demo.DemoNoteService;
import com.studyagent.service.application.demo.NoteVerlaCommandDispatcher;
import com.studyagent.service.application.demo.dto.NoteTurnContext;
import com.studyagent.service.application.demo.dto.NoteTurnPreparation;
import com.studyagent.service.domain.demo.note.NoteConversation;
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
        boolean firstTurn = DemoNoteService.isFirstTurnInput(req.getSourceType(), req.getObjectId(), req.getText());

        NoteTurnPreparation prepared = firstTurn
                ? service.prepareFirstTurn(clerkUserId, id, req.getSourceType(), req.getObjectId(), req.getText(),
                        req.getFilename(), req.getCourseName(), req.getLearningGoal(),
                        req.getOutputLanguage(), req.getMessage())
                : service.prepareFollowUpTurn(clerkUserId, id, req.getMessage());

        NoteTurnContext turn = NoteTurnContext.builder()
                .firstTurn(firstTurn)
                .message(req.getMessage())
                .outputLanguage(req.getOutputLanguage())
                // 首轮的文档基线恒为空：会话刚被重置，不存在可参考的编辑器基线
                .documentContentMd(firstTurn ? "" : req.getDocumentContentMd())
                .documentBaseVersion(firstTurn ? 0L : req.getDocumentBaseVersion())
                .history(prepared.getHistory())
                .build();

        return Result.success(NoteGenerateResponseVO.from(
                commandDispatcher.dispatch(prepared.getConversation(), turn)));
    }
}
