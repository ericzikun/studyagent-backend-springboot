package com.studyagent.infra.verla.dispatch;

import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityRunDispatchGateImplTest {

    @Test
    void defersWhenDetectionActiveCountReachedMax() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        when(repository.countActiveCapabilityRuns(VerlaCommandAction.CMD_DETECTION_RUN.getCode()))
                .thenReturn(10);

        CapabilityRunDispatchGateImpl gate = new CapabilityRunDispatchGateImpl(repository, 10, 20);

        assertTrue(gate.isEnabled(VerlaCommandAction.CMD_DETECTION_RUN.getCode()));
        assertFalse(gate.canDispatchNow(VerlaCommandAction.CMD_DETECTION_RUN.getCode()));
    }

    @Test
    void allowsHumanizerWhenBelowMax() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        when(repository.countActiveCapabilityRuns(VerlaCommandAction.CMD_HUMANIZER_RUN.getCode()))
                .thenReturn(5);

        CapabilityRunDispatchGateImpl gate = new CapabilityRunDispatchGateImpl(repository, 10, 20);

        assertTrue(gate.canDispatchNow(VerlaCommandAction.CMD_HUMANIZER_RUN.getCode()));
    }

    @Test
    void disabledWhenMaxConcurrencyZero() {
        VerlaSessionRepository repository = mock(VerlaSessionRepository.class);
        CapabilityRunDispatchGateImpl gate = new CapabilityRunDispatchGateImpl(repository, 0, 0);

        assertFalse(gate.isEnabled(VerlaCommandAction.CMD_DETECTION_RUN.getCode()));
        assertTrue(gate.canDispatchNow(VerlaCommandAction.CMD_DETECTION_RUN.getCode()));
    }
}
