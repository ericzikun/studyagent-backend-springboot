package com.studyagent.service.domain.user;

import java.util.Optional;

/**
 * 用户Repository接口
 */
public interface UserRepository {
    Optional<User> findById(UserId id);
    Optional<User> findByClerkUserId(String clerkUserId);
    User save(User user);
    void delete(UserId id);
}

