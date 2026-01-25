package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.UserInfoResponse;
import com.studyagent.service.application.AuthApplicationService;
import com.studyagent.service.domain.user.User;
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
        User user = authApplicationService.getCurrentUser(token);
        
        UserInfoResponse response = UserInfoResponse.builder()
            .uid(user.getClerkUserId())
            .displayName(user.getDisplayName())
            .locale(user.getLocale())
            .isAdmin(user.getIsAdmin())
            .emailVerified(false) // TODO: 从 Clerk 获取
            .build();
        
        return Result.success(response);
    }
}

