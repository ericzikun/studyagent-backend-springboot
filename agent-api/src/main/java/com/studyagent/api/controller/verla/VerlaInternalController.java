package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.MessagePageVO;
import com.studyagent.api.dto.verla.response.VerlaConversationContextVO;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.dto.verla.response.VerlaAttachmentVO;
import com.studyagent.api.dto.verla.response.VerlaSessionContextVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.application.verla.VerlaContextQueryService;
import com.studyagent.service.application.verla.dto.VerlaSessionContextQueryOptions;
import com.studyagent.service.application.verla.dto.VerlaSessionContextView;
import com.studyagent.service.domain.verla.VerlaConversation;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Verla 内部接口控制器（Java → Py 暴露的反查上下文 API）
 * <p>
 * 鉴权由 {@link com.studyagent.api.filter.VerlaInternalAuthFilter} 拦截 {@code /v1/internal/verla/*}。
 * 详见 docs/verla-Java侧MVP技术方案.md §10。
 */
@Slf4j
@RestController
@RequestMapping("/v1/internal/verla")
@RequiredArgsConstructor
public class VerlaInternalController {

    private final VerlaContextQueryService contextQueryService;
    private final VerlaAttachmentService attachmentService;
    private final VerlaConversationRepository conversationRepository;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaMessageRepository messageRepository;

    // ====================================================================
    // 1b) GET /v1/internal/verla/conversations/{cid}/context
    //     Py 按会话拉消息历史（支持 before 分页）+ 最新 turn / artifacts / trace
    // ====================================================================
    @GetMapping("/conversations/{conversationId}/context")
    public Result<VerlaConversationContextVO> getConversationContext(
            @PathVariable Long conversationId,
            @RequestParam(value = "convVersion", required = false) Long convVersion,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "includeTrace", defaultValue = "false") boolean includeTrace,
            @RequestParam(value = "includeToolSummaries", defaultValue = "false") boolean includeToolSummaries,
            @RequestParam(value = "includeArtifacts", defaultValue = "true") boolean includeArtifacts,
            @RequestParam(value = "messageLimit", required = false) Integer messageLimit,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "traceLimit", required = false) Integer traceLimit) {
        Integer effectiveLimit = limit != null ? limit : messageLimit;
        log.info("[verla-internal] getConversationContext cid={} convVer={} before={} trace={} summaries={} arts={} msgLim={} traceLim={}",
                conversationId, convVersion, before, includeTrace, includeToolSummaries,
                includeArtifacts, effectiveLimit, traceLimit);
        VerlaSessionContextQueryOptions opts = VerlaSessionContextQueryOptions.builder()
                .includeTrace(includeTrace)
                .includeToolSummaries(includeToolSummaries)
                .includeArtifacts(includeArtifacts)
                .messageLimit(effectiveLimit)
                .traceLimit(traceLimit)
                .build();
        var view = contextQueryService.getConversationContext(conversationId, convVersion, before, opts);
        return Result.success(VerlaConversationContextVO.from(view));
    }

    // ====================================================================
    // 1) GET /v1/internal/verla/sessions/{sid}/context
    //    Py 在 session 启动时一把拉全
    // ====================================================================
    @GetMapping("/sessions/{sessionId}/context")
    public Result<VerlaSessionContextVO> getSessionContext(
            @PathVariable Long sessionId,
            @RequestParam(value = "convVersion", required = false) Long convVersion,
            @RequestParam(value = "turnVersion", required = false) Long turnVersion,
            @RequestParam(value = "includeTrace", defaultValue = "false") boolean includeTrace,
            @RequestParam(value = "includeToolSummaries", defaultValue = "false") boolean includeToolSummaries,
            @RequestParam(value = "includeArtifacts", defaultValue = "true") boolean includeArtifacts,
            @RequestParam(value = "messageLimit", required = false) Integer messageLimit,
            @RequestParam(value = "traceLimit", required = false) Integer traceLimit) {
        log.info("[verla-internal] getSessionContext sid={} convVer={} turnVer={} trace={} summaries={} arts={} msgLim={} traceLim={}",
                sessionId, convVersion, turnVersion, includeTrace, includeToolSummaries,
                includeArtifacts, messageLimit, traceLimit);
        VerlaSessionContextQueryOptions opts = VerlaSessionContextQueryOptions.builder()
                .includeTrace(includeTrace)
                .includeToolSummaries(includeToolSummaries)
                .includeArtifacts(includeArtifacts)
                .messageLimit(messageLimit)
                .traceLimit(traceLimit)
                .build();
        VerlaSessionContextView view = contextQueryService.getSessionContext(sessionId, convVersion, turnVersion, opts);
        return Result.success(VerlaSessionContextVO.from(view));
    }

    // ====================================================================
    // 2) GET /v1/internal/verla/conversations/{cid}/messages
    // ====================================================================
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<MessagePageVO> getRecentMessages(
            @PathVariable Long conversationId,
            @RequestParam(value = "before", required = false) Long beforeId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        log.info("[verla-internal] getRecentMessages cid={} before={} limit={}",
                conversationId, beforeId, limit);
        VerlaConversation c = conversationRepository.findById(conversationId);
        if (c == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<VerlaMessage> page = messageRepository.findByCursor(conversationId, beforeId, safeLimit);
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(MessagePageVO.builder()
                .items(page.stream().map(VerlaMessageVO::from).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .build());
    }

    // ====================================================================
    // 3) GET /v1/internal/verla/conversations/{cid}
    // ====================================================================
    @GetMapping("/conversations/{conversationId}")
    public Result<VerlaConversationVO> getConversation(@PathVariable Long conversationId) {
        VerlaConversation c = conversationRepository.findById(conversationId);
        if (c == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        return Result.success(VerlaConversationVO.from(c));
    }

    // ====================================================================
    // 4) GET /v1/internal/verla/turns/{tid}
    // ====================================================================
    @GetMapping("/turns/{turnId}")
    public Result<VerlaTurn> getTurn(@PathVariable Long turnId) {
        VerlaTurn t = turnRepository.findById(turnId);
        if (t == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        return Result.success(t);
    }

    // ====================================================================
    // 5) GET /v1/internal/verla/sessions/{sid}
    // ====================================================================
    @GetMapping("/sessions/{sessionId}")
    public Result<VerlaSession> getSession(@PathVariable Long sessionId) {
        VerlaSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        return Result.success(s);
    }

    // ====================================================================
    // 6) GET /v1/internal/verla/attachments/{objectId}
    //    Py 拉附件元数据 + storage_uri（local file://）
    // ====================================================================
    @GetMapping("/attachments/{objectId}")
    public Result<VerlaAttachmentVO> getAttachment(@PathVariable String objectId) {
        log.info("[verla-internal] getAttachment objectId={}", objectId);
        return Result.success(VerlaAttachmentVO.fromInternal(attachmentService.getForInternal(objectId)));
    }
}
