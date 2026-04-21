package com.studyagent.service.domain.notify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifySendResult {

    private boolean success;
    private String deliveryId;
    private String errorMessage;
    private boolean retryable;
}
