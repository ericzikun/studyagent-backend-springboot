package com.studyagent.infra.mapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingFulfillmentOpenAggregate {
    private String purchaseType;
    private String productCode;
    private String state;
    private Long openCount;
    private LocalDateTime oldestAcceptedAt;
}
