package com.studyagent.infra.repository.emaillead;

import com.studyagent.infra.mapper.PublicEmailLeadMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicEmailLeadRepositoryImplTest {

    @Test
    void checksExistingEmailWithoutLoadingPiiRecord() {
        PublicEmailLeadMapper mapper = mock(PublicEmailLeadMapper.class);
        PublicEmailLeadRepositoryImpl repository = new PublicEmailLeadRepositoryImpl(mapper);
        when(mapper.existsByNormalizedEmail("existing@example.com")).thenReturn(1);

        assertThat(repository.existsByNormalizedEmail("existing@example.com")).isTrue();
        assertThat(repository.existsByNormalizedEmail("missing@example.com")).isFalse();
    }

    @Test
    void reportsWhetherInsertIgnoreCreatedANewRow() {
        PublicEmailLeadMapper mapper = mock(PublicEmailLeadMapper.class);
        PublicEmailLeadRepositoryImpl repository = new PublicEmailLeadRepositoryImpl(mapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 10, 0);
        when(mapper.insertIfAbsent("new@example.com", "/tools", now)).thenReturn(1);
        when(mapper.insertIfAbsent("existing@example.com", "/use-cases", now)).thenReturn(0);

        assertThat(repository.insertIfAbsent("new@example.com", "/tools", now)).isTrue();
        assertThat(repository.insertIfAbsent("existing@example.com", "/use-cases", now)).isFalse();
    }
}
