package com.studyagent.service.application.verla.admin;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserDomainService;
import com.studyagent.service.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Ops / admin console access control via {@code user_profiles.is_admin}.
 */
@Service
@RequiredArgsConstructor
public class VerlaAdminAccessService {

    private final UserRepository userRepository;
    private final UserDomainService userDomainService;

    public void assertAdmin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (!isAdmin(clerkUserId)) {
            throw new BusinessException(ApiCode.NO_PERMISSION, "admin access denied");
        }
    }

    public boolean isAdmin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return false;
        }
        User user = userRepository.findByClerkUserId(clerkUserId.trim()).orElse(null);
        return userDomainService.isAdmin(user);
    }
}
