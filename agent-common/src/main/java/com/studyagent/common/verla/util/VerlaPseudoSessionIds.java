package com.studyagent.common.verla.util;

import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 附件解析命令没有真实 Verla session 时，用稳定伪 sessionId 做 MQ 保序与 inbox 划分。
 * <p>
 * 见 docs/V2/5.1 §8：finalize 可能早于 agent session；correlationId 仍沿用 conv:turn:sess 三元组格式。
 */
@UtilityClass
public class VerlaPseudoSessionIds {

    /**
     * 由 objectId 派生稳定正 long，用作信封 {@code session.sessionId} 与 correlationId 末段。
     */
    public static long forAttachmentParse(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return 1L;
        }
        UUID u = UUID.nameUUIDFromBytes(objectId.getBytes(StandardCharsets.UTF_8));
        long x = u.getMostSignificantBits() ^ u.getLeastSignificantBits();
        long abs = x == Long.MIN_VALUE ? 1L : Math.abs(x);
        return abs == 0 ? 1L : abs;
    }
}
