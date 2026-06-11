package com.studyagent.common.verla.id;

import lombok.experimental.UtilityClass;

/**
 * 将内部 Long 主键映射为 API 响应中的 public id 字符串。
 */
@UtilityClass
public final class VerlaPublicIdMapper {

    public static String conversation(Long id) {
        return VerlaPublicIdCodec.encode(VerlaPublicIdType.CONVERSATION, id);
    }

    public static String turn(Long id) {
        return VerlaPublicIdCodec.encode(VerlaPublicIdType.TURN, id);
    }

    public static String session(Long id) {
        return VerlaPublicIdCodec.encode(VerlaPublicIdType.SESSION, id);
    }

    public static String message(Long id) {
        return VerlaPublicIdCodec.encode(VerlaPublicIdType.MESSAGE, id);
    }

    public static String artifact(Long id) {
        return VerlaPublicIdCodec.encode(VerlaPublicIdType.ARTIFACT, id);
    }
}
