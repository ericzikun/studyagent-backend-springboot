package com.studyagent.service.application.verla;

import com.github.benmanes.caffeine.cache.Cache;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaConversationRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import com.studyagent.service.config.VerlaContextCacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VerlaContextQueryServiceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(VerlaConversationRepository.class, () -> mock(VerlaConversationRepository.class))
            .withBean(VerlaTurnRepository.class, () -> mock(VerlaTurnRepository.class))
            .withBean(VerlaSessionRepository.class, () -> mock(VerlaSessionRepository.class))
            .withBean(VerlaMessageRepository.class, () -> mock(VerlaMessageRepository.class))
            .withBean(VerlaArtifactRepository.class, () -> mock(VerlaArtifactRepository.class))
            .withBean(VerlaToolCallRepository.class, () -> mock(VerlaToolCallRepository.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(VerlaContextCacheProperties.class)
            .withBean(VerlaContextQueryService.class);

    @Test
    void shouldBindDurationStyleCacheConfigKeys() {
        contextRunner.withPropertyValues(
                "verla.context-cache.conv-summary-ttl=77s",
                "verla.context-cache.turn-meta-ttl=33s",
                "verla.context-cache.sess-meta-ttl=11s",
                "verla.context-cache.max-entries-per-layer=777"
        ).run(context -> {
            VerlaContextQueryService service = context.getBean(VerlaContextQueryService.class);

            Cache<?, ?> convCache = cacheField(service, "convCache");
            Cache<?, ?> turnCache = cacheField(service, "turnCache");
            Cache<?, ?> sessCache = cacheField(service, "sessCache");

            assertThat(expireAfterWriteSeconds(convCache)).isEqualTo(77L);
            assertThat(expireAfterWriteSeconds(turnCache)).isEqualTo(33L);
            assertThat(expireAfterWriteSeconds(sessCache)).isEqualTo(11L);
            assertThat(maximumSize(convCache)).isEqualTo(777L);
        });
    }

    @SuppressWarnings("unchecked")
    private static Cache<?, ?> cacheField(VerlaContextQueryService service, String fieldName) {
        return (Cache<?, ?>) ReflectionTestUtils.getField(service, fieldName);
    }

    private static long expireAfterWriteSeconds(Cache<?, ?> cache) {
        return cache.policy()
                .expireAfterWrite()
                .orElseThrow()
                .getExpiresAfter(TimeUnit.SECONDS);
    }

    private static long maximumSize(Cache<?, ?> cache) {
        return cache.policy()
                .eviction()
                .orElseThrow()
                .getMaximum();
    }
}
