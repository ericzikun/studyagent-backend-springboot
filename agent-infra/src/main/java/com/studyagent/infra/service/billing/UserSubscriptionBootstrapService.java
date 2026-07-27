package com.studyagent.infra.service.billing;

import com.studyagent.infra.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserSubscriptionBootstrapService {
    private final UserSubscriptionMapper userSubscriptionMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(String clerkUserId) {
        userSubscriptionMapper.insertFreeIfAbsent(clerkUserId, LocalDateTime.now());
    }
}
