package com.studyagent.common.verla.enums;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.Locale;

/**
 * 历史载荷可能仍发出 {@code "AGENT"}；当前枚举改名为 {@link VerlaSessionKind#ASSIGNMENT}。
 * 兼容旧消息/DLX 重投。
 */
public class VerlaSessionKindDeserializer extends JsonDeserializer<VerlaSessionKind> {

    @Override
    public VerlaSessionKind deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if ("AGENT".equalsIgnoreCase(v)) {
            return VerlaSessionKind.ASSIGNMENT;
        }
        try {
            return VerlaSessionKind.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw InvalidFormatException.from(
                    p,
                    "Unknown VerlaSessionKind: " + raw,
                    raw,
                    VerlaSessionKind.class);
        }
    }
}
