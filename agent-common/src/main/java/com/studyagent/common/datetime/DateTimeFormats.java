package com.studyagent.common.datetime;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * API 层时间格式化约定。
 * <p>
 * 库表 {@code DATETIME} 与 Java {@link LocalDateTime} 存的是 UTC 墙钟（容器默认时区为 UTC，
 * 与 {@link com.studyagent.common.verla.envelope.LenientInstantDeserializer} 无时区按 UTC 解释一致）。
 * 序列化时补上 {@code Z}，避免前端把无时区字符串当本地时间解析产生 8 小时偏差。
 */
public final class DateTimeFormats {

    /** API 输出时假定 {@link LocalDateTime} 的墙钟时区 */
    public static final ZoneOffset API_ZONE = ZoneOffset.UTC;

    private static final DateTimeFormatter API_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateTimeFormats() {
    }

    public static String formatApi(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atOffset(API_ZONE).format(API_FORMATTER);
    }
}
