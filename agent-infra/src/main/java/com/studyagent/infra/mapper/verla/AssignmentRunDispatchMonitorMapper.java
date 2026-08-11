package com.studyagent.infra.mapper.verla;

import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchTaskQueryRow;
import com.studyagent.infra.metrics.AssignmentDispatchMetricsSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AssignmentRunDispatchMonitorMapper {

    @Select("SELECT "
            + "COALESCE(SUM(CASE WHEN o.status = 0 THEN 1 ELSE 0 END), 0) AS pending, "
            + "COALESCE(TIMESTAMPDIFF(SECOND, "
            + "MIN(CASE WHEN o.status = 0 THEN o.created_at END), NOW()), 0) AS oldest_age_seconds, "
            + "COUNT(DISTINCT CASE WHEN o.status = 0 THEN s.id END) AS queued, "
            + "COUNT(DISTINCT CASE WHEN o.status = 1 AND s.status = 'DISPATCHING' THEN s.id END) "
            + "AS dispatching, "
            + "COUNT(DISTINCT CASE WHEN s.status = 'RUNNING' THEN s.id END) AS running "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry')")
    AssignmentDispatchMetricsSnapshot selectAssignmentDispatchMetrics();

    @Select("SELECT "
            + "s.id AS session_id, "
            + "s.conversation_id, "
            + "s.turn_id, "
            + "s.status AS session_status, "
            + "s.feature_code, "
            + "s.kind, "
            + "s.started_at, "
            + "s.ended_at, "
            + "s.created_at AS session_created_at, "
            + "c.user_id AS clerk_user_id, "
            + "o.id AS outbox_id, "
            + "o.status AS outbox_status, "
            + "o.action AS outbox_action, "
            + "o.created_at AS outbox_created_at "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "LEFT JOIN verla_conversations c ON c.id = s.conversation_id "
            + "ORDER BY COALESCE(s.started_at, s.created_at) DESC "
            + "LIMIT #{limit}")
    List<AssignmentRunDispatchTaskQueryRow> selectRecentAssignmentRuns(@Param("limit") int limit);

    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "WHERE s.status = #{terminalStatus} "
            + "AND s.ended_at IS NOT NULL "
            + "AND s.ended_at >= #{since}")
    Integer countTerminalAssignmentRunsSince(@Param("terminalStatus") String terminalStatus,
                                             @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "WHERE COALESCE(s.started_at, s.created_at) >= #{since}")
    Integer countStartedAssignmentRunsSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "WHERE COALESCE(s.started_at, s.created_at) >= #{start} "
            + "AND COALESCE(s.started_at, s.created_at) < #{end}")
    Integer countStartedAssignmentRunsBetween(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "WHERE s.status = #{terminalStatus} "
            + "AND s.ended_at IS NOT NULL "
            + "AND s.ended_at >= #{start} "
            + "AND s.ended_at < #{end}")
    Integer countTerminalAssignmentRunsBetween(@Param("terminalStatus") String terminalStatus,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    /**
     * 按单一 outbox action 统计启动 session 数（Detection / Humanizer 等）。
     * 调用方需传入与 session 落库时区一致的时间窗（报表侧对 UTC 墙钟做 BJT→UTC 换算）。
     */
    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action = #{action} "
            + "WHERE COALESCE(s.started_at, s.created_at) >= #{start} "
            + "AND COALESCE(s.started_at, s.created_at) < #{end}")
    Integer countStartedRunsByActionBetween(@Param("action") String action,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(DISTINCT s.id) "
            + "FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "AND o.action = #{action} "
            + "WHERE s.status = #{terminalStatus} "
            + "AND s.ended_at IS NOT NULL "
            + "AND s.ended_at >= #{start} "
            + "AND s.ended_at < #{end}")
    Integer countTerminalRunsByActionBetween(@Param("action") String action,
                                             @Param("terminalStatus") String terminalStatus,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM mq_outbox "
            + "WHERE status = 0 "
            + "AND action IN ('cmd.assignment.run', 'cmd.agent.control.retry')")
    Integer countPendingAssignmentRunOutbox();

    /**
     * 全局排队 session 数：存在 UNSENT run/retry outbox 的去重 session。
     */
    @Select("SELECT COUNT(DISTINCT session_id) FROM mq_outbox "
            + "WHERE status = 0 "
            + "AND action IN ('cmd.assignment.run', 'cmd.agent.control.retry')")
    Integer countQueuedAssignmentRunSessions();
}
