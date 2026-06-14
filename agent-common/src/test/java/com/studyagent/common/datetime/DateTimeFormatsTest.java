package com.studyagent.common.datetime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeFormatsTest {

    @Test
    void formatApi_shouldAppendShanghaiOffset() {
        LocalDateTime value = LocalDateTime.of(2026, 6, 3, 12, 0, 0);

        assertThat(DateTimeFormats.formatApi(value)).isEqualTo("2026-06-03T12:00:00+08:00");
        assertThat(DateTimeFormats.formatApi(null)).isNull();
    }

    @Test
    void fromInstant_shouldConvertUtcInstantToShanghaiWallClock() {
        Instant utcNoon = Instant.parse("2026-06-03T04:00:00Z");

        assertThat(DateTimeFormats.fromInstant(utcNoon))
                .isEqualTo(LocalDateTime.of(2026, 6, 3, 12, 0, 0));
    }

    @Test
    void toEpochSecond_shouldTreatWallClockAsShanghai() {
        LocalDateTime value = LocalDateTime.of(2026, 6, 3, 12, 0, 0);

        assertThat(DateTimeFormats.toEpochSecond(value))
                .isEqualTo(Instant.parse("2026-06-03T04:00:00Z").getEpochSecond());
    }

    @Test
    void offsetSerializer_shouldSerializeLocalDateTimeWithZone() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .registerModule(new JavaTimeModule())
                .registerModule(new SimpleModule()
                        .addSerializer(LocalDateTime.class, new OffsetLocalDateTimeSerializer()));

        record Payload(LocalDateTime createdAt) {
        }

        String json = objectMapper.writeValueAsString(new Payload(LocalDateTime.of(2026, 6, 3, 12, 0, 0)));

        assertThat(json).contains("\"createdAt\":\"2026-06-03T12:00:00+08:00\"");
    }
}
