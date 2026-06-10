package com.studyagent.api.controller.verla;

import com.studyagent.api.web.verla.VerlaPublicId;
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
        // 校验所有权（即使 conv 已 archived 也允许订阅历史事件，但 deleted 不允许）
        conversationService.getOwned(clerkUserId, cid);

        Long lastId = parseLastEventId(lastEventIdHeader, lastEventIdParam);
        log.info("[Verla/sse] subscribe cid={} userId={} lastEventId={}", cid, clerkUserId, lastId);
        return sseGateway.register(cid, lastId);
    }

    private static Long parseLastEventId(String header, Long fromParam) {
        if (fromParam != null && fromParam > 0) {
            return fromParam;
        }
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long v = Long.parseLong(header.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
