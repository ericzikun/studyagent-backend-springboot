package com.studyagent.infra.metrics;

import com.studyagent.infra.mapper.verla.AssignmentRunDispatchMonitorMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentDispatchMetricsSqlContractTest {

    @Test
    void realtimeMetricsUseOneBoundedAggregateQueryWithoutHistoryWindows() throws Exception {
        Method method = AssignmentRunDispatchMonitorMapper.class
                .getMethod("selectAssignmentDispatchMetrics");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("cmd.assignment.run"));
        assertTrue(sql.contains("cmd.agent.control.retry"));
        assertTrue(sql.contains("status = 0"));
        assertTrue(sql.contains("min("));
        assertTrue(sql.contains("queued"));
        assertTrue(sql.contains("dispatching"));
        assertTrue(sql.contains("running"));
        assertFalse(sql.contains("interval 1 hour"));
        assertFalse(sql.contains("interval 24 hour"));
        assertFalse(sql.contains("limit"));
    }
}
