package com.studyagent.common.verla.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerlaPublicIdCodecTest {

    @Test
    void encodeDecodeConversationRoundTrip() {
        String publicId = VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, 42L);
        assertTrue(publicId.startsWith("vc_"));
        assertEquals(42L, VerlaPublicIdCodec.requireInternalId(VerlaPublicIdType.CONVERSATION, publicId));
    }

    @Test
    void dualReadAcceptsPlainNumericDuringMigration() {
        assertEquals(42L, VerlaPublicIdCodec.requireInternalId(VerlaPublicIdType.CONVERSATION, "42"));
    }

    @Test
    void rejectsMismatchedPrefix() {
        String turnId = VerlaPublicIdCodec.encode(VerlaPublicIdType.TURN, 7L);
        assertThrows(IllegalArgumentException.class,
                () -> VerlaPublicIdCodec.requireInternalId(VerlaPublicIdType.CONVERSATION, turnId));
    }

    @Test
    void legacyTaskIdRoundTripWithoutPrefix() {
        String encoded = VerlaPublicIdCodec.encodeLegacyTaskId(123L);
        assertNotNull(encoded);
        assertTrue(!encoded.contains("_"));
        assertEquals(123L, VerlaPublicIdCodec.decodeLegacyTaskId(encoded));
    }

    @Test
    void mapperProducesTypedPrefixes() {
        assertTrue(VerlaPublicIdMapper.conversation(1L).startsWith("vc_"));
        assertTrue(VerlaPublicIdMapper.turn(1L).startsWith("vt_"));
        assertTrue(VerlaPublicIdMapper.session(1L).startsWith("vs_"));
        assertTrue(VerlaPublicIdMapper.message(1L).startsWith("vm_"));
    }
}
