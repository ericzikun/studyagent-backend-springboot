package com.studyagent.service.application.verla.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

public class VerlaCacheJsonCodec {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public VerlaCacheJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String encode(Long version, Object data) {
        CacheEnvelope<Object> envelope = CacheEnvelope.builder()
                .schemaVersion(SCHEMA_VERSION)
                .cachedAt(OffsetDateTime.now())
                .version(version)
                .data(data)
                .build();
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode verla cache payload", e);
        }
    }

    public <T> CacheEnvelope<T> decode(String json, TypeReference<CacheEnvelope<T>> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to decode verla cache payload", e);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheEnvelope<T> {
        private int schemaVersion;
        private OffsetDateTime cachedAt;
        private Long version;
        private T data;
    }
}
