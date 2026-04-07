package com.studyagent.api.service.robot;

import com.studyagent.service.application.NotifyApplicationService;
import com.studyagent.service.application.dto.NotifyDispatchResult;
import com.studyagent.service.application.request.NotifyDispatchRequest;
import com.studyagent.service.config.NotifyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 同步调用 Notify API（钉钉），供异步封装使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotNotifyService {

    private final NotifyApplicationService notifyApplicationService;
    private final NotifyConfig notifyConfig;

    public void dispatch(String eventId, String scene, String title, String markdownContent, Map<String, Object> metadata) {
        if (!notifyConfig.isEnabled()) {
            log.debug("robot notify skipped: notify.enabled=false");
            return;
        }
        if (notifyConfig.getApiToken() == null || notifyConfig.getApiToken().isBlank()) {
            log.debug("robot notify skipped: notify api token empty");
            return;
        }
        NotifyDispatchRequest req = NotifyDispatchRequest.builder()
                .eventId(eventId)
                .sourceService("springboot_backend")
                .scene(scene)
                .title(title)
                .content(markdownContent)
                .level("info")
                .contentType("markdown")
                .env(notifyConfig.getDefaultEnv())
                .metadata(metadata != null ? metadata : Collections.emptyMap())
                .build();
        try {
            NotifyDispatchResult result = notifyApplicationService.dispatch(req, notifyConfig.getApiToken());
            if (result.getCode() != 0) {
                log.warn("robot notify rejected: code={}, msg={}, eventId={}", result.getCode(), result.getMessage(), eventId);
            }
        } catch (Exception e) {
            log.warn("robot notify error: eventId={}, {}", eventId, e.getMessage(), e);
        }
    }
}
