package com.studyagent.api.service.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportSessionTimeWindowsTest {

    @Test
    void bjtWallToUtcWall_shiftsMinusEightHours() {
        LocalDateTime bjt = LocalDateTime.of(2026, 7, 11, 0, 0, 0);
        assertEquals(LocalDateTime.of(2026, 7, 10, 16, 0, 0),
                ReportSessionTimeWindows.bjtWallToUtcWall(bjt));
        assertEquals(LocalDateTime.of(2026, 7, 11, 16, 0, 0),
                ReportSessionTimeWindows.bjtWallToUtcWall(LocalDateTime.of(2026, 7, 12, 0, 0, 0)));
    }

    @Test
    void bjtWallToUtcWall_nullSafe() {
        assertNull(ReportSessionTimeWindows.bjtWallToUtcWall(null));
    }
}
