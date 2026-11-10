package com.studyagent.infra.service.simulation;

import com.stripe.model.checkout.Session;
import com.studyagent.infra.entity.SimulationPassEntity;
import com.studyagent.infra.entity.SimulationPassProductEntity;
import com.studyagent.infra.mapper.SimulationPassMapper;
import com.studyagent.infra.mapper.SimulationPassProductMapper;
import com.studyagent.infra.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationPassServiceTest {

    private SimulationPassMapper passMapper;
    private SimulationPassProductMapper productMapper;
    private UserMapper userMapper;
    private SimulationPassService service;

    @BeforeEach
    void setUp() {
        passMapper = mock(SimulationPassMapper.class);
        productMapper = mock(SimulationPassProductMapper.class);
        userMapper = mock(UserMapper.class);
        service = new SimulationPassService(passMapper, productMapper, userMapper);
        when(productMapper.selectOne(any())).thenReturn(product());
    }

    @Test
    void adminWithoutPassStillHasAccess() {
        when(passMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectIsAdmin("user_1")).thenReturn(true);

        SimulationPassService.Access access = service.access("user_1");

        assertTrue(access.active());
        assertTrue(access.admin());
        assertEquals(199, access.amountCents());
        assertEquals(7, access.accessDays());
    }

    @Test
    void userWithoutPassOrAdminIsLocked() {
        when(passMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectIsAdmin(anyString())).thenReturn(false);

        assertFalse(service.access("user_2").active());
    }

    @Test
    void fulfillGrantsSevenDaysAndSkipsDuplicateSession() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_test_sim");
        when(session.getPaymentIntent()).thenReturn("pi_test_sim");
        when(session.getAmountTotal()).thenReturn(199L);
        when(session.getCurrency()).thenReturn("usd");
        when(passMapper.selectCount(any())).thenReturn(0L);
        when(passMapper.selectOne(any())).thenReturn(null);

        service.fulfillCheckout("user_1", session);

        ArgumentCaptor<SimulationPassEntity> captor =
                ArgumentCaptor.forClass(SimulationPassEntity.class);
        verify(passMapper).insert(captor.capture());
        SimulationPassEntity pass = captor.getValue();
        assertEquals("user_1", pass.getClerkUserId());
        assertEquals("cs_test_sim", pass.getStripeCheckoutSessionId());
        assertEquals("pi_test_sim", pass.getStripePaymentIntentId());
        assertEquals(199, pass.getAmountCents());
        assertEquals("usd", pass.getCurrency());
        assertTrue(pass.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
    }

    @Test
    void duplicateCheckoutSessionIsIgnored() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_test_sim");
        when(passMapper.selectCount(any())).thenReturn(1L);

        service.fulfillCheckout("user_1", session);

        verify(passMapper, never()).insert(any(SimulationPassEntity.class));
    }

    private SimulationPassProductEntity product() {
        SimulationPassProductEntity product = new SimulationPassProductEntity();
        product.setPassCode(SimulationPassService.PASS_CODE);
        product.setStripePriceId("price_test_sim");
        product.setAmountCents(199);
        product.setCurrency("usd");
        product.setAccessDays(7);
        product.setIsActive(true);
        return product;
    }
}
