package com.studyagent.common.verla.enums;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.Locale;

/**
 * Python 历史载荷曾发出 {@code "ASSIGNMENT"}；Java 枚举仅为 {@link VerlaSessionKind#AGENT}。
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
        if ("ASSIGNMENT".equalsIgnoreCase(v)) {
            return VerlaSessionKind.AGENT;
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
