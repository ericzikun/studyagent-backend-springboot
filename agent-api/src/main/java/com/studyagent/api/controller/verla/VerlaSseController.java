package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
import com.studyagent.common.verla.id.LegacyConversationIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.api.sse.VerlaSseGateway;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Verla SSE 订阅入口（PR-16）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §13.1 / §13.3。\
 * <ul>
 *     <li>{@code GET /v1/verla/conversations/{cid}/events}</li>
 *     <li>支持 {@code Last-Event-ID} header 或 {@code lastEventId} query 参数补发</li>
 *     <li>EventSource 不支持自定义 header，前端需在 URL 上带 {@code ?access_token=...}，由 AuthInterceptor 兼容</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla")
@RequiredArgsConstructor
public class VerlaSseController {

    private final VerlaConversationService conversationService;
    private final VerlaSseGateway sseGateway;

    @GetMapping(value = "/conversations/{cid}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @VerlaPublicId(VerlaPublicIdType.CONVERSATION) @PathVariable Long cid,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @RequestParam(value = "lastEventId", required = false) Long lastEventIdParam) {
        ensureLogin(clerkUserId);
        // 1.0 历史任务无任何运行时事件：发一个 readonly 帧后立刻关闭，避免空连接挂起
        if (LegacyConversationIdCodec.isLegacy(cid)) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("legacy-readonly")
                        .data(java.util.Map.of("source", "LEGACY_1_0")));
                emitter.complete();
            } catch (java.io.IOException ignored) {
                emitter.complete();
            }
            return emitter;
        }
        // 校验所有权（即使 conv 已 archived 也允许订阅历史事件，但 deleted 不允许）
        conversationService.getOwned(clerkUserId, cid);

        Long lastId = parseLastEventId(lastEventIdHeader, lastEventIdParam);
        log.info("[Verla/sse] subscribe cid={} userId={} lastEventId={}", cid, clerkUserId, lastId);
        return sseGateway.register(cid, lastId, clerkUserId);
    }

    static Long parseLastEventId(String header, Long fromParam) {
        Long fromHeader = null;
        try {
            if (header != null && !header.isBlank()) {
                long parsed = Long.parseLong(header.trim());
                fromHeader = parsed > 0 ? parsed : null;
            }
        } catch (NumberFormatException nfe) {
            // Ignore a malformed browser cursor and fall back to the URL cursor.
        }
        Long positiveParam = fromParam != null && fromParam > 0 ? fromParam : null;
        if (fromHeader == null) {
            return positiveParam;
        }
        if (positiveParam == null) {
            return fromHeader;
        }
        // Native EventSource reconnect keeps the original query string but adds
        // a newer Last-Event-ID header. Replaying from the max avoids duplicates.
        return Math.max(fromHeader, positiveParam);
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
