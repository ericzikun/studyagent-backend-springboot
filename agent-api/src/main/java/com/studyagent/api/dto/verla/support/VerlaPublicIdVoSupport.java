package com.studyagent.api.dto.verla.support;

import com.studyagent.common.verla.id.VerlaPublicIdMapper;
import com.studyagent.common.verla.id.VerlaPublicIdType;
import lombok.experimental.UtilityClass;

/**
 * 将内部 Long 主键格式化为 API 响应字段（public id 或内部数字字符串）。
 */
@UtilityClass
public final class VerlaPublicIdVoSupport {

    public static String conversation(Long id, boolean encodePublicIds) {
        return format(VerlaPublicIdType.CONVERSATION, id, encodePublicIds);
    }

    public static String turn(Long id, boolean encodePublicIds) {
        return format(VerlaPublicIdType.TURN, id, encodePublicIds);
    }

    public static String session(Long id, boolean encodePublicIds) {
        return format(VerlaPublicIdType.SESSION, id, encodePublicIds);
    }

    public static String message(Long id, boolean encodePublicIds) {
        return format(VerlaPublicIdType.MESSAGE, id, encodePublicIds);
    }

    public static String artifact(Long id, boolean encodePublicIds) {
        return format(VerlaPublicIdType.ARTIFACT, id, encodePublicIds);
    }

    public static String format(VerlaPublicIdType type, Long internalId, boolean encodePublicIds) {
        if (internalId == null) {
            return null;
        }
        if (!encodePublicIds) {
            return String.valueOf(internalId);
        }
        return switch (type) {
            case CONVERSATION -> VerlaPublicIdMapper.conversation(internalId);
            case TURN -> VerlaPublicIdMapper.turn(internalId);
            case SESSION -> VerlaPublicIdMapper.session(internalId);
            case MESSAGE -> VerlaPublicIdMapper.message(internalId);
            case ARTIFACT -> VerlaPublicIdMapper.artifact(internalId);
            default -> throw new IllegalArgumentException("unsupported public id type: " + type);
        };
    }
}
