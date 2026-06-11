package com.studyagent.api.jackson.verla;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.dto.verla.request.VerlaUploadSignRequest;
import com.studyagent.common.verla.id.VerlaPublicIdCodec;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerlaPublicIdLongDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesConversationPublicIdInUploadSignRequest() throws Exception {
        String publicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, 1356L);
        VerlaUploadSignRequest req = objectMapper.readValue(
                """
                {
                  "conversationId": "%s",
                  "filename": "hello.txt",
                  "mime": "text/plain",
                  "sizeBytes": 5
                }
                """.formatted(publicId),
                VerlaUploadSignRequest.class);

        assertEquals(1356L, req.getConversationId());
    }

    @Test
    void deserializesLegacyNumericConversationIdInUploadSignRequest() throws Exception {
        VerlaUploadSignRequest req = objectMapper.readValue(
                """
                {
                  "conversationId": "1356",
                  "filename": "hello.txt",
                  "mime": "text/plain",
                  "sizeBytes": 5
                }
                """,
                VerlaUploadSignRequest.class);

        assertEquals(1356L, req.getConversationId());
    }
}
