package com.studyagent.infra.service.payment;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.infra.entity.AiFeaturePackageEntity;
import com.studyagent.infra.mapper.AiFeaturePackageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentDomainServiceImplTest {

    @Mock
    private AiFeaturePackageMapper aiFeaturePackageMapper;

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

        PaymentDomainServiceImpl service = new PaymentDomainServiceImpl(aiFeaturePackageMapper);
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
        PaymentDomainServiceImpl service = new PaymentDomainServiceImpl(aiFeaturePackageMapper);
        ReflectionTestUtils.setField(service, "paymentCheckoutMockEnabled", true);

        var result = service.getSessionStatus("mock_cs_123");

        assertEquals("mock_cs_123", result.getSessionId());
        assertEquals("complete", result.getStatus());
        assertEquals("paid", result.getPaymentStatus());
        assertEquals("usd", result.getCurrency());
    }
}
