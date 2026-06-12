package com.studyagent.common.datetime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 将 {@link LocalDateTime} 序列化为带 offset 的 ISO-8601 字符串（如 {@code 2026-06-03T12:00:00+08:00}）。
 */
public class OffsetLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(DateTimeFormats.formatApi(value));
    }
}
