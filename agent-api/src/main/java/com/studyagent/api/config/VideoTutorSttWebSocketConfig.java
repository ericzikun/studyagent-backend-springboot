package com.studyagent.api.config;

import com.studyagent.service.application.verla.stt.VideoTutorSttHandler;
import com.studyagent.service.application.verla.stt.VideoTutorSttHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** clone 语音输入：/v1/verla/conversations/{cid}/video-tutor/stt */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class VideoTutorSttWebSocketConfig implements WebSocketConfigurer {

    private final VideoTutorSttHandler handler;
    private final VideoTutorSttHandshakeInterceptor interceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/v1/verla/conversations/*/video-tutor/stt")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
