package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BillingReviewNotifyRequest {
    String stripeEventId;
    String stripeEventType;
    String objectId;
    String status;
    String reason;
    int attemptCount;
}
