package com.studyagent.api.controller;

import com.studyagent.api.common.Meta;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.NotifyEventRequest;
import com.studyagent.api.dto.response.NotifyErrorResponse;
import com.studyagent.api.dto.response.NotifyEventResponse;
import com.studyagent.common.log.util.TraceIdUtil;
import com.studyagent.service.application.NotifyApplicationService;
import com.studyagent.service.application.dto.NotifyDispatchResult;
import com.studyagent.service.application.request.NotifyDispatchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyApplicationService notifyApplicationService;

    @PostMapping("/events")
    public Result<NotifyEventResponse> notifyEvent(
            @RequestBody NotifyEventRequest request,
            @RequestHeader(value = "X-Notify-Token", required = false) String notifyToken
    ) {
        long startAt = System.currentTimeMillis();
        NotifyDispatchRequest dispatchRequest = NotifyDispatchRequest.builder()
                .eventId(request.getEventId())
                .sourceService(request.getSourceService())
                .scene(request.getScene())
                .title(request.getTitle())
                .content(request.getContent())
                .level(request.getLevel())
                .contentType(request.getContentType())
                .env(request.getEnv())
                .timestamp(request.getTimestamp())
                .metadata(request.getMetadata())
                .build();

        NotifyDispatchResult dispatchResult = notifyApplicationService.dispatch(dispatchRequest, notifyToken);
        NotifyEventResponse response = toApiResponse(dispatchResult);
        Result<NotifyEventResponse> result;

        if (dispatchResult.getCode() == 0) {
            result = Result.success(response);
        } else {
            result = new Result<>();
            result.setMeta(Meta.error(dispatchResult.getCode(), dispatchResult.getMessage()));
            result.setData(response);
        }

        logRequest(dispatchResult, result, System.currentTimeMillis() - startAt);
        return result;
    }

    private NotifyEventResponse toApiResponse(NotifyDispatchResult dispatchResult) {
        NotifyDispatchResult.NotifyDispatchData data = dispatchResult.getData();
        NotifyErrorResponse error = null;
        if (data != null && data.getError() != null) {
            error = NotifyErrorResponse.builder()
                    .type(data.getError().getType())
                    .detail(data.getError().getDetail())
                    .retryable(data.getError().isRetryable())
                    .build();
        }

        if (data == null) {
            return NotifyEventResponse.builder()
                    .status("failed")
                    .error(error)
                    .build();
        }

        return NotifyEventResponse.builder()
                .eventId(data.getEventId())
                .sourceService(data.getSourceService())
                .scene(data.getScene())
                .level(data.getLevel())
                .contentType(data.getContentType())
                .env(data.getEnv())
                .status(data.getStatus())
                .deliveryId(data.getDeliveryId())
                .error(error)
                .build();
    }

    private void logRequest(NotifyDispatchResult dispatchResult, Result<NotifyEventResponse> result, long durationMs) {
        NotifyDispatchResult.NotifyDispatchData data = dispatchResult == null ? null : dispatchResult.getData();
        String traceId = result != null && result.getMeta() != null ? result.getMeta().getTraceId() : TraceIdUtil.getTraceId();
        String eventId = data == null ? null : data.getEventId();
        String sourceService = data == null ? null : data.getSourceService();
        String scene = data == null ? null : data.getScene();
        String level = data == null ? null : data.getLevel();
        String status = data == null ? "failed" : data.getStatus();

        log.info("notify api request: traceId={}, eventId={}, sourceService={}, scene={}, level={}, status={}, durationMs={}",
                traceId, eventId, sourceService, scene, level, status, durationMs);
    }
}
