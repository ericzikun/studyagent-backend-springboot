package com.studyagent.service.domain.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaVipAccessServiceTest {

    private QuotaVipRepository repository;
    private QuotaVipAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(QuotaVipRepository.class);
        service = new QuotaVipAccessService(repository);
    }

    @Test
    void isQuotaVip_returnsFalse_forBlank() {
        assertFalse(service.isQuotaVip(null));
        assertFalse(service.isQuotaVip("  "));
    }

    @Test
    void isQuotaVip_cachesRepositoryResult() {
        when(repository.existsActiveVip("user_vip")).thenReturn(true);

        assertTrue(service.isQuotaVip("user_vip"));
        assertTrue(service.isQuotaVip("user_vip"));

        verify(repository, times(1)).existsActiveVip("user_vip");
    }

    @Test
    void invalidate_forcesReload() {
        when(repository.existsActiveVip("user_vip")).thenReturn(true, false);

        assertTrue(service.isQuotaVip("user_vip"));
        service.invalidate("user_vip");
        assertFalse(service.isQuotaVip("user_vip"));

        verify(repository, times(2)).existsActiveVip("user_vip");
    }
}
