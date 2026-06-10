package com.studyagent.infra.mapper.verla;

import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchTaskQueryRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AssignmentRunDispatchMonitorMapper {

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

    @Select("SELECT COUNT(*) FROM mq_outbox "
            + "WHERE status = 0 "
            + "AND action IN ('cmd.assignment.run', 'cmd.agent.control.retry')")
    Integer countPendingAssignmentRunOutbox();
}
