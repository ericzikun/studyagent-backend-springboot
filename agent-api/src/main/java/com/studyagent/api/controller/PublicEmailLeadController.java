package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.PublicEmailLeadRequest;
import com.studyagent.api.dto.response.PublicEmailLeadAcceptedResponse;
import com.studyagent.api.web.ClientIpResolver;
import com.studyagent.common.log.annotation.ApiLog;
import com.studyagent.service.application.emaillead.PublicEmailLeadApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无登录态的公开邮箱留资入口。
 *
 * <p>响应不区分首次、重复或蜜罐命中；请求日志明确关闭，避免邮箱进入通用 API 日志。</p>
 */
@RestController
@RequestMapping("/v1/public/email-leads")
@RequiredArgsConstructor
public class PublicEmailLeadController {

    private final PublicEmailLeadApplicationService applicationService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    @ApiLog(description = "公开邮箱留资", logRequest = false)
    public ResponseEntity<Result<PublicEmailLeadAcceptedResponse>> capture(
            @RequestBody PublicEmailLeadRequest request,
            HttpServletRequest httpRequest) {
        applicationService.capture(
                request.getEmail(),
                request.getSource(),
                request.getCompanyWebsite(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(new PublicEmailLeadAcceptedResponse(true)));
    }
}
