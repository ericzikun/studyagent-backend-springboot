package com.studyagent.common.datetime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 将「UTC 墙钟」语义的 {@link LocalDateTime} 序列化为带 {@code Z} 的 ISO-8601。
 * <p>用于容器时区为 UTC、直接写 {@code LocalDateTime.now()} 的 note demo 表；
 * 不能复用全局 {@link OffsetLocalDateTimeSerializer}（它固定补 +08:00）。
 */
public class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(DateTimeFormats.formatUtcApi(value));
    }
}
