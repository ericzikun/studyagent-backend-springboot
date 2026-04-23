package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.UserInfoResponse;
import com.studyagent.service.application.AuthApplicationService;
import com.studyagent.service.application.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthApplicationService authApplicationService;
    
    @GetMapping("/me")
    public Result<UserInfoResponse> getCurrentUser(
            @RequestHeader("Authorization") String token) {
        AuthenticatedUser auth = authApplicationService.getCurrentUser(token);
        var user = auth.user();
        var tokenInfo = auth.tokenInfo();

        Boolean verified = tokenInfo.emailVerified != null ? tokenInfo.emailVerified : Boolean.FALSE;

        UserInfoResponse response = UserInfoResponse.builder()
            .uid(user.getClerkUserId())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .locale(user.getLocale())
            .isAdmin(user.getIsAdmin())
            .emailVerified(verified)
            .build();
        
        return Result.success(response);
    }
}

