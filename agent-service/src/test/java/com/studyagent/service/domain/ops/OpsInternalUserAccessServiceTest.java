package com.studyagent.service.domain.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsInternalUserAccessServiceTest {

    private OpsInternalUserRepository repository;
    private OpsInternalUserAccessService service;

    @BeforeEach
    void setUp() {
        repository = mock(OpsInternalUserRepository.class);
        service = new OpsInternalUserAccessService(repository);
    }

    @Test
    void listActiveClerkUserIds_cachesRepositoryResult() {
        when(repository.listActiveClerkUserIds()).thenReturn(List.of("user_a", "user_b"));

        assertEquals(List.of("user_a", "user_b"), service.listActiveClerkUserIds());
        assertEquals(List.of("user_a", "user_b"), service.listActiveClerkUserIds());
        verify(repository, times(1)).listActiveClerkUserIds();
    }

    @Test
    void isInternal_cachesMembership() {
        when(repository.existsActiveInternal("user_internal")).thenReturn(true);

        assertTrue(service.isInternal("user_internal"));
        assertTrue(service.isInternal("user_internal"));
        verify(repository, times(1)).existsActiveInternal("user_internal");
    }

    @Test
    void isInternal_returnsFalseForBlank() {
        assertFalse(service.isInternal(null));
        assertFalse(service.isInternal("  "));
    }
}
