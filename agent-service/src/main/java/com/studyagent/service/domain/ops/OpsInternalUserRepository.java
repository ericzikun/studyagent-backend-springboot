package com.studyagent.service.domain.ops;

import java.util.List;

/**
 * Persistence for {@code ops_internal_users}.
 */
public interface OpsInternalUserRepository {

    /**
     * @return active internal clerk user ids
     */
    List<String> listActiveClerkUserIds();

    /**
     * @return true if clerk user is currently marked as an active internal user
     */
    boolean existsActiveInternal(String clerkUserId);
}
