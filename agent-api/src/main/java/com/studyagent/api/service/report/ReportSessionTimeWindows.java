package com.studyagent.api.service.report;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 日报/周报时间窗换算。
 * <p>
 * {@code verla_conversations} 等业务表按 BJT 墙钟写入；{@code verla_sessions} /
 * {@code mq_outbox} 的 started_at / ended_at / created_at 实际落库为 UTC 墙钟。
 * 报表对外统一按 BJT 日/周切窗，查 session/outbox 时需把 BJT 窗换到 UTC 墙钟再比对。
 */
public final class ReportSessionTimeWindows {

    public static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private ReportSessionTimeWindows() {
    }

    /** BJT LocalDateTime → 同一瞬间的 UTC 墙钟 LocalDateTime（写入 session 列的形态）。 */
    public static LocalDateTime bjtWallToUtcWall(LocalDateTime bjtWallClock) {
        if (bjtWallClock == null) {
            return null;
        }
        return bjtWallClock.atZone(BJT).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
