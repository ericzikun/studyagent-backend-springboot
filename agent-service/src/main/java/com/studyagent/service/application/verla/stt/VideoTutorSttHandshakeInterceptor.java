package com.studyagent.service.application.verla.stt;

import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.user.ClerkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** STT WebSocket 握手鉴权：校验 Clerk token + 会话归属，再把 cid/user 放进 session attributes。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTutorSttHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern PATH = Pattern.compile(
            ".*/v1/verla/conversations/([^/]+)/video-tutor/stt/?$");

    private final ClerkClient clerkClient;
    private final VerlaConversationService conversationService;

    @Value("${auth.dev-bypass.enabled:false}")
    private boolean devBypassEnabled;

    @Value("${auth.dev-bypass.default-user:dev-user-001}")
    private String devBypassDefaultUser;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        try {
            Matcher matcher = PATH.matcher(request.getURI().getPath());
            if (!matcher.matches()) {
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return false;
            }
            Long conversationId = VerlaPublicIdCodec.requireInternalId(
                    VerlaPublicIdType.CONVERSATION, matcher.group(1));
            String userId = resolveUserId(request);
            if (userId == null || userId.isBlank()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            conversationService.getOwned(userId, conversationId);
            attributes.put("conversationId", conversationId);
            attributes.put("clerkUserId", userId);
            return true;
        } catch (Exception e) {
            log.info("[video-tutor/stt] handshake rejected: {}", e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private String resolveUserId(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Object verified = servletRequest.getServletRequest().getAttribute("clerkUserId");
            if (verified instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        String token = tokenFrom(request);
        if (token == null || token.isBlank()) {
            return devBypassEnabled ? devBypassDefaultUser : null;
        }
        try {
            ClerkClient.UserInfo info = clerkClient.verifyToken(token);
            return info == null ? null : info.clerkUserId;
        } catch (Exception e) {
            return null;
        }
    }

    private static String tokenFrom(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("access_token");
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // no-op
    }
}
