package com.studyagent.service.application.emaillead;

import com.studyagent.common.exception.PublicWriteProtectionUnavailableException;
import com.studyagent.common.exception.RateLimitExceededException;
import com.studyagent.service.domain.emaillead.PublicEmailLeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicEmailLeadApplicationServiceTest {

    @Mock
    private PublicEmailLeadRepository repository;

    @Mock
    private PublicEmailLeadWriteGuard writeGuard;

    private PublicEmailLeadApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PublicEmailLeadApplicationService(repository, writeGuard);
    }

    @Test
    void normalizesEmailAndPersistsFirstSource() {
        when(repository.insertIfAbsent(eq("person@example.com"), eq("/tools/ai-detector"), any()))
                .thenReturn(true);

        service.capture("  Person@Example.COM ", "/tools/ai-detector", "", "203.0.113.8");

        ArgumentCaptor<LocalDateTime> createdAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(writeGuard).checkIpRateLimit("203.0.113.8");
        verify(writeGuard).reserveDailyNew();
        verify(repository).insertIfAbsent(
                eq("person@example.com"),
                eq("/tools/ai-detector"),
                createdAt.capture());
        assertThat(createdAt.getValue()).isNotNull();
        verify(writeGuard, never()).releaseDailyReservation();
    }

    @Test
    void existingEmailDoesNotCompeteForDailyNewBudget() {
        when(repository.existsByNormalizedEmail("person@example.com")).thenReturn(true);

        service.capture("person@example.com", "/use-cases", null, "203.0.113.8");

        verify(writeGuard).checkIpRateLimit("203.0.113.8");
        verify(writeGuard, never()).reserveDailyNew();
        verify(repository, never()).insertIfAbsent(any(), any(), any());
        verify(writeGuard, never()).releaseDailyReservation();
    }

    @Test
    void concurrentDuplicateReturnsReservationWithoutOverwriting() {
        when(repository.insertIfAbsent(any(), any(), any())).thenReturn(false);

        service.capture("person@example.com", "/use-cases", null, "203.0.113.8");

        verify(writeGuard).reserveDailyNew();
        verify(writeGuard).releaseDailyReservation();
    }

    @Test
    void honeypotShortCircuitsBeforeValidationAndPersistence() {
        service.capture(null, null, "https://spam.example", "203.0.113.8");

        verifyNoInteractions(repository, writeGuard);
    }

    @Test
    void invalidEmailOrSourceDoesNotConsumeWriteBudget() {
        assertThatThrownBy(() -> service.capture("invalid", "/tools", "", "203.0.113.8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email is invalid");
        assertThatThrownBy(() -> service.capture("person@example.com", "/tools?ref=spam", "", "203.0.113.8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("source is invalid");

        verifyNoInteractions(repository, writeGuard);
    }

    @Test
    void databaseFailureReturnsReservedDailySlot() {
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        when(repository.insertIfAbsent(any(), any(), any())).thenThrow(databaseFailure);

        assertThatThrownBy(() -> service.capture(
                "person@example.com", "/tools", "", "203.0.113.8"))
                .isSameAs(databaseFailure);

        verify(writeGuard).releaseDailyReservation();
    }

    @Test
    void writeProtectionFailureNeverReachesMysql() {
        PublicWriteProtectionUnavailableException redisFailure =
                new PublicWriteProtectionUnavailableException();
        doThrow(redisFailure).when(writeGuard).checkIpRateLimit("203.0.113.8");

        assertThatThrownBy(() -> service.capture(
                "person@example.com", "/tools", "", "203.0.113.8"))
                .isSameAs(redisFailure);

        verify(repository, never()).insertIfAbsent(any(), any(), any());
        verify(writeGuard, never()).releaseDailyReservation();
    }

    @Test
    void exhaustedDailyBudgetCannotReachInsertPathForNewEmail() {
        RateLimitExceededException budgetFailure =
                new RateLimitExceededException("public-email-leads");
        doThrow(budgetFailure).when(writeGuard).reserveDailyNew();

        assertThatThrownBy(() -> service.capture(
                "new@example.com", "/tools", "", "203.0.113.8"))
                .isSameAs(budgetFailure);

        verify(repository).existsByNormalizedEmail("new@example.com");
        verify(repository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void releaseFailureDoesNotHideDatabaseFailure() {
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        RuntimeException redisFailure = new RuntimeException("redis unavailable");
        when(repository.insertIfAbsent(any(), any(), any())).thenThrow(databaseFailure);
        doThrow(redisFailure).when(writeGuard).releaseDailyReservation();

        assertThatThrownBy(() -> service.capture(
                "person@example.com", "/tools", "", "203.0.113.8"))
                .isSameAs(databaseFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(redisFailure));
    }
}
