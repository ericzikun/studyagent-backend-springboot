package com.studyagent.service.application.verla.admin;

import lombok.Builder;
import lombok.Value;

/**
 * Owner profile fields attached to admin conversation rows.
 */
@Value
@Builder
public class AdminOwnerProfile {
    String clerkUserId;
    String displayName;
    String email;
    String country;
    /** free / plus / pro / … */
    String membershipType;
    String planCode;
    String tier;
    boolean quotaVip;
    boolean admin;
}
