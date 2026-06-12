package com.studyagent.api.jackson.verla;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;

import java.io.IOException;

/**
 * Jackson deserializer for internal {@code Long} fields that accept V2 public ids
 * ({@code vc_*}, {@code vt_*}, …) or migration-era numeric values in JSON bodies.
 */
public class VerlaPublicIdLongDeserializer extends JsonDeserializer<Long> implements ContextualDeserializer {

    private final VerlaPublicIdType expectedType;

    public VerlaPublicIdLongDeserializer() {
        this(VerlaPublicIdType.CONVERSATION);
    }

    private VerlaPublicIdLongDeserializer(VerlaPublicIdType expectedType) {
        this.expectedType = expectedType;
    }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return switch (parser.currentToken()) {
            case VALUE_NULL -> null;
            case VALUE_NUMBER_INT -> parser.getLongValue();
            case VALUE_STRING -> decodeString(parser.getText());
            default -> throw context.weirdStringException(
                    parser.getText(),
                    Long.class,
                    "expected numeric or public id string");
        };
    }

    private Long decodeString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return VerlaPublicIdCodec.requireInternalId(expectedType, trimmed);
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        VerlaPublicIdField annotation = property == null
                ? null
                : property.getAnnotation(VerlaPublicIdField.class);
        VerlaPublicIdType type = annotation == null
                ? VerlaPublicIdType.CONVERSATION
                : annotation.value();
        return new VerlaPublicIdLongDeserializer(type);
    }
}
