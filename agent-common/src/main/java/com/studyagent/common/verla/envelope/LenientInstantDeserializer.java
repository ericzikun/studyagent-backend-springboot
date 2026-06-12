package com.studyagent.common.verla.envelope;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 兼容 Py/MySQL 等处序列化出的无时区 ISO-8601（如 {@code 2026-05-01T11:09:41.512545}），
 * Jackson 默认 {@link Instant} 解析要求带 {@code Z} 或偏移量。
 * <p>
 * 无时区字符串按 UTC 解释（适用于 Python {@code datetime.now(timezone.utc)} 等带 UTC 语义的事件）。
 * 写入 MySQL {@code DATETIME} 时请用 {@link com.studyagent.common.datetime.DateTimeFormats#fromInstant(Instant)}。
 */
public class LenientInstantDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw InvalidFormatException.from(
                    p,
                    "Cannot deserialize java.time.Instant from \"" + text + "\"",
                    text,
                    Instant.class);
        }
    }
}
