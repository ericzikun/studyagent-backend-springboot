package com.studyagent.api.util;

import com.studyagent.common.verla.id.VerlaPublicIdCodec;

/**
 * 任务 ID 编解码工具（V1）
 * <p>
 * 使用 Sqids 将数据库自增 ID 编码为短字符串对外暴露，避免用户感知业务规模。
 * 实现委托给 {@link VerlaPublicIdCodec}，与 V2 public id 共用字母表。
 */
public final class TaskIdEncoder {

    private TaskIdEncoder() {}

    /**
     * 将内部 Long 类型的 taskId 编码为对外暴露的短字符串
     */
    public static String encode(Long taskId) {
        return VerlaPublicIdCodec.encodeLegacyTaskId(taskId);
    }

    /**
     * 将对外暴露的短字符串解码为内部 Long 类型的 taskId
     * @return 解码后的 taskId，解码失败返回 null
     */
    public static Long decode(String encoded) {
        return VerlaPublicIdCodec.decodeLegacyTaskId(encoded);
    }
}
