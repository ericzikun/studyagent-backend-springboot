package com.studyagent.api.service.robot;

import com.studyagent.service.application.NotifyApplicationService;
import com.studyagent.service.application.dto.NotifyDispatchResult;
import com.studyagent.service.application.request.NotifyDispatchRequest;
import com.studyagent.service.config.NotifyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * 同步调用 Notify API（飞书机器人），供异步封装使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotNotifyService {

    private final NotifyApplicationService notifyApplicationService;
    private final NotifyConfig notifyConfig;

    /**
     * 使用 {@link RobotNotifyRouteKind#DEFAULT}，即 {@code notify.default-target}。
     */
    public void dispatch(String eventId, String scene, String title, String markdownContent, Map<String, Object> metadata) {
        dispatch(RobotNotifyRouteKind.DEFAULT, eventId, scene, title, markdownContent, metadata);
    }

    public void dispatch(
            RobotNotifyRouteKind routeKind,
            String eventId,
            String scene,
            String title,
            String markdownContent,
            Map<String, Object> metadata
    ) {
        if (!notifyConfig.isEnabled()) {
            log.debug("robot notify skipped: notify.enabled=false");
            return;
        }
        if (notifyConfig.getApiToken() == null || notifyConfig.getApiToken().isBlank()) {
            log.debug("robot notify skipped: notify api token empty");
            return;
        }
        String target = resolveTargetKey(routeKind);
        NotifyDispatchRequest req = NotifyDispatchRequest.builder()
                .eventId(eventId)
                .sourceService("springboot_backend")
                .scene(scene)
                .target(target)
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
                log.warn("robot notify rejected: code={}, msg={}, eventId={}, target={}", result.getCode(), result.getMessage(), eventId, target);
            }
        } catch (Exception e) {
            log.warn("robot notify error: eventId={}, target={}, {}", eventId, target, e.getMessage(), e);
        }
    }

    private String resolveTargetKey(RobotNotifyRouteKind routeKind) {
        NotifyConfig.RobotTarget routes = notifyConfig.getRobotTarget();
        if (routes == null) {
            routes = new NotifyConfig.RobotTarget();
        }
        String key = switch (routeKind) {
            case ASSIGNMENT -> routes.getAssignment();
            case FEEDBACK -> routes.getFeedback();
            case REPORT -> routes.getReport();
            case DEFAULT -> notifyConfig.getDefaultTarget();
        };
        return StringUtils.defaultIfBlank(key, "default");
    }
}
