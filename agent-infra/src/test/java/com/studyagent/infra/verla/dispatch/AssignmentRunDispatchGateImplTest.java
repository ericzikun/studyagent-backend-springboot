package com.studyagent.infra.verla.dispatch;

import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentRunDispatchGateImplTest {

    @Test
    void disabledWhenMaxConcurrencyIsZero() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        AssignmentRunDispatchGateImpl gate = new AssignmentRunDispatchGateImpl(repository, 0);

        assertThat(gate.isEnabled()).isFalse();
        assertThat(gate.canDispatchNow()).isTrue();
    }

    @Test
    void blocksWhenActiveRunsExceedMaxConcurrency() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        when(repository.countActiveAssignmentRuns()).thenReturn(5);
        AssignmentRunDispatchGateImpl gate = new AssignmentRunDispatchGateImpl(repository, 4);

        assertThat(gate.isEnabled()).isTrue();
        assertThat(gate.activeCount()).isEqualTo(5);
        assertThat(gate.canDispatchNow()).isFalse();
    }

    @Test
    void allowsDispatchWhenActiveRunsBelowLimit() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        when(repository.countActiveAssignmentRuns()).thenReturn(3);
        AssignmentRunDispatchGateImpl gate = new AssignmentRunDispatchGateImpl(repository, 4);

        assertThat(gate.canDispatchNow()).isTrue();
    }

    @Test
    void blocksDispatchWhenActiveRunsReachLimit() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        when(repository.countActiveAssignmentRuns()).thenReturn(4);
        AssignmentRunDispatchGateImpl gate = new AssignmentRunDispatchGateImpl(repository, 4);

        assertThat(gate.canDispatchNow()).isFalse();
    }
}
