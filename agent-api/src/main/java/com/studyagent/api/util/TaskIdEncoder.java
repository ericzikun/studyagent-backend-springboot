package com.studyagent.api.util;

import org.sqids.Sqids;

import java.util.Collections;
import java.util.List;

/**
 * 任务 ID 编解码工具
 * 使用 Sqids 将数据库自增 ID 编码为短字符串对外暴露，避免用户感知业务规模
 */
public final class TaskIdEncoder {

    private static final String DEFAULT_ALPHABET = "FxnXM1kBN6cuhsAvjW3Co7l2RePyY8DwaU04Tzt9fHQrqSVKdpimLGIJOgb5ZE";

    private static final Sqids SQIDS = Sqids.builder()
            .alphabet(DEFAULT_ALPHABET)
            .minLength(5)
            .build();

    private TaskIdEncoder() {}

    /**
     * 将内部 Long 类型的 taskId 编码为对外暴露的短字符串
     */
    public static String encode(Long taskId) {
        if (taskId == null) return null;
        return SQIDS.encode(Collections.singletonList(taskId));
    }

    /**
     * 将对外暴露的短字符串解码为内部 Long 类型的 taskId
     * @return 解码后的 taskId，解码失败返回 null
     */
    public static Long decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            List<Long> numbers = SQIDS.decode(encoded);
            return (numbers != null && !numbers.isEmpty()) ? numbers.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
