package com.studyagent.common.datetime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 应用层时间约定。
 * <p>
 * 库表 {@code DATETIME}、JDBC {@code serverTimezone=Asia/Shanghai}、MySQL {@code NOW()}、
 * Docker {@code TZ=Asia/Shanghai} 均按<strong>北京时间墙钟</strong>存储 {@link LocalDateTime}。
 * API 序列化时补上 {@code +08:00}，避免前端把无时区字符串当本地时间或误标 {@code Z} 产生 8 小时偏差。
 * <p>
 * 来自 Python 等外部的 {@link Instant}（UTC 时刻）写入 DATETIME 列前，请用 {@link #fromInstant(Instant)}。
 */
public final class DateTimeFormats {

    /** 应用统一时区，与 JDBC / MySQL / 容器 TZ 一致 */
    public static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    /** API JSON 输出 offset（中国无夏令时，固定 +08:00） */
    public static final ZoneOffset API_ZONE = ZoneOffset.ofHours(8);

    /**
     * quota_ledger 写入改为 {@link #now()} 之前，部分环境把 JVM UTC 墙钟写进 DATETIME，
     * 但 API 仍按 +08:00 输出；读路径需在此时间点前按 UTC 墙钟还原 epoch。
     */
    public static final LocalDateTime QUOTA_LEDGER_UTC_WALL_CLOCK_CUTOFF =
            LocalDateTime.of(2026, 6, 26, 13, 38, 19);

    private static final DateTimeFormatter API_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DateTimeFormats() {
    }

    /** 当前北京时间（写入 DB {@code DATETIME} 推荐使用） */
    public static LocalDateTime now() {
        return LocalDateTime.now(APP_ZONE);
    }

    /** 当前北京日期 */
    public static LocalDate today() {
        return LocalDate.now(APP_ZONE);
    }

    /** UTC {@link Instant} → 北京时间墙钟（写入 DATETIME 列） */
    public static LocalDateTime fromInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, APP_ZONE);
    }

    /** {@link Instant} 为空时回退为 {@link #now()} */
    public static LocalDateTime fromInstantOrNow(Instant instant) {
        return instant == null ? now() : fromInstant(instant);
    }

    /** 北京时间墙钟 → Unix epoch 秒（供 API 返回秒级时间戳） */
    public static long toEpochSecond(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.atZone(APP_ZONE).toEpochSecond();
    }

    public static Long toEpochSecondOrNull(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(APP_ZONE).toEpochSecond();
    }

    /** 序列化为带 offset 的 ISO-8601 字符串（如 {@code 2026-06-03T12:00:00+08:00}） */
    public static String formatApi(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(APP_ZONE).format(API_FORMATTER);
    }

    /**
     * quota_ledger.created_at → Unix epoch 秒。
     * 修复写入时区前，DATETIME 存的是 UTC 墙钟；之后存北京时间墙钟。
     */
    public static long toQuotaLedgerCreatedAtEpochSecond(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        if (value.isBefore(QUOTA_LEDGER_UTC_WALL_CLOCK_CUTOFF)) {
            return value.atZone(ZoneOffset.UTC).toEpochSecond();
        }
        return toEpochSecond(value);
    }
}
