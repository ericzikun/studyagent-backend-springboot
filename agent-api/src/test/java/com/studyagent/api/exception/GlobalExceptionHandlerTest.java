package com.studyagent.api.exception;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.InsufficientQuotaResponse;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.exception.CommercialBlockData;
import com.studyagent.common.exception.CurrentPlanData;
import com.studyagent.common.exception.InsufficientQuotaData;
import com.studyagent.common.exception.InsufficientQuotaException;
import com.studyagent.service.domain.billing.BillingDomainService;
import com.studyagent.service.domain.billing.BillingPlan;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    @Test
    void handleInsufficientQuotaException_enrichesCurrentPlan() {
        BillingDomainService billingDomainService = Mockito.mock(BillingDomainService.class);
        Mockito.when(billingDomainService.getEffectivePlanOrFree("user_1"))
                .thenReturn(BillingPlan.freePlan());
        GlobalExceptionHandler handler = new GlobalExceptionHandler(billingDomainService);

        InsufficientQuotaException ex = new InsufficientQuotaException(
                "Insufficient quota",
                InsufficientQuotaData.builder()
                        .clerkUserId("user_1")
                        .featureCode("task_create")
                        .purchaseProductId("assignment")
                        .blockedAction("assignment_generate")
                        .quotaUnit("count")
                        .freeBalance(0L)
                        .freePeriodTotal(1L)
                        .paidBalance(0L)
                        .totalAvailable(0L)
                        .build());

        Result<InsufficientQuotaResponse> result = handler.handleInsufficientQuotaException(ex);

        assertEquals(1011, result.getMeta().getStatusCode());
        assertNotNull(result.getData());
        assertEquals("assignment", result.getData().getPurchaseProductId());
        assertEquals("assignment_generate", result.getData().getBlockedAction());
        assertNotNull(result.getData().getCurrentPlan());
        assertEquals("free", result.getData().getCurrentPlan().getPlanCode());
        assertEquals("free", result.getData().getCurrentPlan().getTier());
    }

    @Test
    void handleBusinessException_preservesStructuredData() {
        BillingDomainService billingDomainService = Mockito.mock(BillingDomainService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(billingDomainService);
        CommercialBlockData payload = CommercialBlockData.builder()
                .reasonCode("output_type_not_allowed")
                .purchaseProductId("assignment")
                .currentPlan(CurrentPlanData.builder()
                        .planCode("free")
                        .tier("free")
                        .isPaid(false)
                        .build())
                .unsupportedOutputTypes(List.of("ppt"))
                .build();

        BusinessException ex = BusinessException.withData(ApiCode.OUTPUT_TYPE_NOT_ALLOWED, payload);

        Result<Object> result = handler.handleBusinessException(ex);

        assertEquals(1013, result.getMeta().getStatusCode());
        CommercialBlockData data = assertInstanceOf(CommercialBlockData.class, result.getData());
        assertEquals("output_type_not_allowed", data.getReasonCode());
        assertEquals(List.of("ppt"), data.getUnsupportedOutputTypes());
    }

    @Test
    void handleInsufficientQuotaException_treatsNullTierPaidPlanAsPaid() {
        BillingDomainService billingDomainService = Mockito.mock(BillingDomainService.class);
        Mockito.when(billingDomainService.getEffectivePlanOrFree("user_2"))
                .thenReturn(BillingPlan.builder()
                        .planCode("plus_monthly")
                        .tier(null)
                        .build());
        GlobalExceptionHandler handler = new GlobalExceptionHandler(billingDomainService);

        InsufficientQuotaException ex = new InsufficientQuotaException(
                "Insufficient quota",
                InsufficientQuotaData.builder()
                        .clerkUserId("user_2")
                        .featureCode("humanizer")
                        .purchaseProductId("humanizer")
                        .blockedAction("humanizer_start")
                        .build());

        Result<InsufficientQuotaResponse> result = handler.handleInsufficientQuotaException(ex);

        assertNotNull(result.getData());
        assertNotNull(result.getData().getCurrentPlan());
        assertEquals("plus_monthly", result.getData().getCurrentPlan().getPlanCode());
        assertEquals(Boolean.TRUE, result.getData().getCurrentPlan().getIsPaid());
    }
}
