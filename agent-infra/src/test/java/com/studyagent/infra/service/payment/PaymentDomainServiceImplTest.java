package com.studyagent.infra.service.payment;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AiFeaturePackageEntity;
import com.studyagent.infra.entity.RechargeOrderEntity;
import com.studyagent.infra.mapper.AiFeaturePackageMapper;
import com.studyagent.infra.mapper.RechargeOrderMapper;
import com.studyagent.service.domain.payment.PaymentDomainException;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentDomainServiceImplTest {

    @Mock
    private AiFeaturePackageMapper aiFeaturePackageMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;

    @Test
    void getPaymentConfigIncludesFeatureUnits() {
        AiFeaturePackageEntity detection = new AiFeaturePackageEntity();
        detection.setFeatureCode("ai_detection");
        detection.setPackageCode("detection_10k");
        detection.setPackageName("10,000 Words");
        detection.setQuotaAmount(10_000L);
        detection.setPriceCents(199);
        detection.setCurrency("usd");
        detection.setLabel("normal");

        AiFeaturePackageEntity assignment = new AiFeaturePackageEntity();
        assignment.setFeatureCode("task_create");
        assignment.setPackageCode("assignment_3");
        assignment.setPackageName("3 Assignments");
        assignment.setQuotaAmount(3L);
        assignment.setPriceCents(999);
        assignment.setCurrency("usd");
        assignment.setLabel("normal");

        when(aiFeaturePackageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(detection, assignment));

        PaymentDomainServiceImpl service =
                new PaymentDomainServiceImpl(aiFeaturePackageMapper, rechargeOrderMapper,
                    new com.studyagent.infra.service.billing.BillingPricingService(org.mockito.Mockito.mock(com.studyagent.infra.service.billing.StripePriceGateway.class), new com.studyagent.infra.service.billing.BillingRegionalPricingProperties()), org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
        ReflectionTestUtils.setField(service, "stripePublishableKey", "pk_test_123");

        var result = service.getPaymentConfig();

        assertEquals("pk_test_123", result.getStripePublishableKey());
        assertEquals("words", result.getPackages().get(0).get("unit"));
        assertEquals("time", result.getPackages().get(1).get("unit"));
        assertEquals(10_000, result.getPackages().get(0).get("credits"));
        assertEquals("ai_detection", result.getPackages().get(0).get("featureCode"));
    }

    @Test
    void getSessionStatusReturnsPaidForMockCheckoutSession() {
        PaymentDomainServiceImpl service =
                new PaymentDomainServiceImpl(aiFeaturePackageMapper, rechargeOrderMapper,
                    new com.studyagent.infra.service.billing.BillingPricingService(org.mockito.Mockito.mock(com.studyagent.infra.service.billing.StripePriceGateway.class), new com.studyagent.infra.service.billing.BillingRegionalPricingProperties()), org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class));
        ReflectionTestUtils.setField(service, "paymentCheckoutMockEnabled", true);

        var result = service.getSessionStatus("mock_cs_123");

        assertEquals("mock_cs_123", result.getSessionId());
        assertEquals("complete", result.getStatus());
        assertEquals("paid", result.getPaymentStatus());
        assertEquals("usd", result.getCurrency());
    }

    @Test
    void getSessionStatusRejectsStripeMetadataThatConflictsWithLocalOrderOwner() {
        RechargeOrderEntity order = new RechargeOrderEntity();
        order.setStripeSessionId("cs_owner_conflict");
        order.setClerkUserId("user_local_owner");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        PaymentDomainServiceImpl service =
                new PaymentDomainServiceImpl(aiFeaturePackageMapper, rechargeOrderMapper,
                    new com.studyagent.infra.service.billing.BillingPricingService(org.mockito.Mockito.mock(com.studyagent.infra.service.billing.StripePriceGateway.class), new com.studyagent.infra.service.billing.BillingRegionalPricingProperties()), org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
                    @Override
                    Session retrieveStripeCheckoutSession(String sessionId) {
                        Session session = new Session();
                        session.setId(sessionId);
                        session.setMetadata(java.util.Map.of(
                                "clerk_user_id", "user_metadata_owner"));
                        return session;
                    }
                };

        PaymentDomainException error = assertThrows(
                PaymentDomainException.class,
                () -> service.getSessionStatus("cs_owner_conflict"));

        assertEquals("SESSION_OWNER_MISMATCH", error.getCode());
    }
    @Test
    void replacementSessionContextRequiresLocalOrderReplacementAndCustomerMembership() {
        var order = new RechargeOrderEntity(); order.setOrderNo("order_replacement"); order.setOrderType("subscription_replacement");
        order.setClerkUserId("user_recovery"); order.setStripeSessionId("cs_replacement"); order.setTargetPlanCode("pro_monthly");
        when(rechargeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        var replacements = org.mockito.Mockito.mock(com.studyagent.infra.mapper.SubscriptionRecoveryReplacementMapper.class);
        var recoveries = org.mockito.Mockito.mock(com.studyagent.infra.mapper.SubscriptionPaymentRecoveryMapper.class);
        var replacement = new com.studyagent.infra.entity.SubscriptionRecoveryReplacementEntity();
        replacement.setOrderNo("order_replacement"); replacement.setClerkUserId("user_recovery"); replacement.setCheckoutSessionId("cs_replacement");
        replacement.setRecoveryId(91L); replacement.setOldSubscriptionId("sub_old"); replacement.setTargetPlanCode("pro_monthly");
        var root = new com.studyagent.infra.entity.SubscriptionPaymentRecoveryEntity(); root.setId(91L);
        root.setClerkUserId("user_recovery"); root.setStripeSubscriptionId("sub_old"); root.setStripeCustomerId("cus_recovery");
        when(replacements.selectBySession("cs_replacement")).thenReturn(replacement);
        when(recoveries.selectById(91L)).thenReturn(root);
        var service = replacementSessionService();
        ReflectionTestUtils.setField(service, "recoveryReplacementMapper", replacements);
        ReflectionTestUtils.setField(service, "paymentRecoveryMapper", recoveries);
        var result = service.getSessionStatus("cs_replacement");
        assertEquals("subscription_replacement", result.getPurchaseType()); assertEquals("91", result.getRecoveryId());
        assertEquals("order_replacement", result.getOrderNo()); assertEquals("pro_monthly", result.getTargetPlanCode());
        root.setStripeCustomerId("cus_other");
        assertEquals("SESSION_OWNER_MISMATCH", assertThrows(PaymentDomainException.class,
                () -> service.getSessionStatus("cs_replacement")).getCode());
    }

    @Test
    void replacementSessionRejectsUnvalidatedMetadataContext() {
        var service = replacementSessionService();
        assertEquals("SESSION_OWNER_MISMATCH", assertThrows(PaymentDomainException.class,
                () -> service.getSessionStatus("cs_replacement")).getCode());
    }

    private PaymentDomainServiceImpl replacementSessionService() {
        return new PaymentDomainServiceImpl(aiFeaturePackageMapper, rechargeOrderMapper,
                new com.studyagent.infra.service.billing.BillingPricingService(org.mockito.Mockito.mock(com.studyagent.infra.service.billing.StripePriceGateway.class), new com.studyagent.infra.service.billing.BillingRegionalPricingProperties()), org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
            @Override Session retrieveStripeCheckoutSession(String id) {
                var session = new Session(); session.setId(id); session.setCustomer("cus_recovery");
                session.setMetadata(java.util.Map.of("purchase_type", "subscription_replacement", "clerk_user_id", "user_recovery",
                        "recovery_id", "91", "old_subscription_id", "sub_old", "order_no", "order_replacement", "plan_code", "pro_monthly"));
                return session;
            }
        };
    }

    @Test
    void sessionStatusReturnsAuthoritativeStudyPassPurchaseType() {
        PaymentDomainServiceImpl service = new PaymentDomainServiceImpl(aiFeaturePackageMapper, rechargeOrderMapper,
                    new com.studyagent.infra.service.billing.BillingPricingService(org.mockito.Mockito.mock(com.studyagent.infra.service.billing.StripePriceGateway.class), new com.studyagent.infra.service.billing.BillingRegionalPricingProperties()), org.mockito.Mockito.mock(com.studyagent.service.domain.user.ClerkClient.class)) {
            @Override
            Session retrieveStripeCheckoutSession(String id) {
                Session session = new Session();
                session.setId(id);
                session.setMetadata(java.util.Map.of("purchase_type", "study_pass", "clerk_user_id", "user_study"));
                return session;
            }
        };
        var result = service.getSessionStatus("cs_study");
        assertEquals("study_pass", result.getPurchaseType());
        assertEquals("user_study", result.getClerkUserId());
    }

}
