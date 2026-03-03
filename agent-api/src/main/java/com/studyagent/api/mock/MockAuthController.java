package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.UserInfoResponse;
import com.studyagent.common.api.ApiCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class MockAuthController {

    private final MockAuthSupport mockAuthSupport;

    @GetMapping("/me")
    public Result<UserInfoResponse> getCurrentUser(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        UserInfoResponse response = UserInfoResponse.builder()
            .uid(user.uid())
            .email(user.email())
            .displayName(user.displayName())
            .avatarUrl(user.avatarUrl())
            .locale(user.locale())
            .isAdmin(user.isAdmin())
            .emailVerified(user.emailVerified())
            .createdAt(user.createdAt())
            .lastLoginAt(user.lastLoginAt())
            .build();
        return Result.success(response);
    }
}
