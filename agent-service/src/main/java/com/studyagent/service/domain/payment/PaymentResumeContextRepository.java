package com.studyagent.service.domain.payment;

import java.time.LocalDateTime;

public interface PaymentResumeContextRepository {

    PaymentResumeContext save(PaymentResumeContext context);

    PaymentResumeContext findByTokenForUpdate(String resumeToken);

    void markResumed(Long id, LocalDateTime resumedAt);
}
