package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface VerlaSessionMapper extends BaseMapper<VerlaSessionEntity> {

    @Select("SELECT * FROM verla_sessions WHERE id = #{id} FOR UPDATE")
    VerlaSessionEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM verla_sessions "
            + "WHERE turn_id = #{turnId} ORDER BY id ASC")
    List<VerlaSessionEntity> selectByTurn(@Param("turnId") Long turnId);

    @Select("<script>"
            + "SELECT * FROM verla_sessions WHERE turn_id IN "
            + "<foreach item='id' collection='turnIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY turn_id, id ASC"
            + "</script>")
    List<VerlaSessionEntity> selectByTurnIds(@Param("turnIds") List<Long> turnIds);

    /**
     * 取同 turn 内已 SUCCEEDED 的兄弟 session（exclude 自身）。
     * 与 docs/verla-Java侧MVP技术方案.md §10.2 SQL B 对齐。
     */
    @Select("SELECT * FROM verla_sessions "
            + "WHERE turn_id = #{turnId} AND status = 'SUCCEEDED' AND id <> #{excludeId} "
            + "ORDER BY ended_at ASC, id ASC")
    List<VerlaSessionEntity> selectCompletedSiblings(@Param("turnId") Long turnId,
                                                     @Param("excludeId") Long excludeId);

    @Select("SELECT * FROM verla_sessions WHERE correlation_id = #{correlationId}")
    VerlaSessionEntity selectByCorrelationId(@Param("correlationId") String correlationId);

    /**
     * 绑定本 session 的扣费流水（V2 商业化）。
     * <p>
     * 乐观保护：仅当 quota_ledger_id 仍为空时才写入，避免并发派发重复扣费。
     * 返回影响行数：1 = 绑定成功；0 = 已有 ledger（或 session 不存在）。
     */
    @Update("UPDATE verla_sessions "
            + "SET quota_ledger_id = #{ledgerId}, quota_amount = #{amount} "
            + "WHERE id = #{id} AND quota_ledger_id IS NULL")
    int bindQuotaLedger(@Param("id") Long id,
                        @Param("ledgerId") Long ledgerId,
                        @Param("amount") Long amount);

    /**
     * 统计占用 assignment run 并发 slot 的 session 数。
     * <p>
     * 仅计已真正占用 slot 的任务：{@code RUNNING}，或 {@code DISPATCHING} 且 run/retry outbox 已 {@code SENT}。
     * {@code DISPATCHING + UNSENT}（门控 defer、尚未发往 MQ）不计入，避免排队任务反向占满 slot 导致死锁。
     */
    @Select("SELECT COUNT(DISTINCT s.id) FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "WHERE o.action IN ('cmd.assignment.run', 'cmd.agent.control.retry') "
            + "AND (s.status = 'RUNNING' "
            + "     OR (s.status = 'DISPATCHING' AND o.status = 1))")
    int countActiveAssignmentRuns();

    /**
     * 统计占用 AI Detection / Humanizer run 派发 slot 的 session 数。
     */
    @Select("SELECT COUNT(DISTINCT s.id) FROM verla_sessions s "
            + "INNER JOIN mq_outbox o ON o.session_id = s.id "
            + "WHERE o.action = #{action} "
            + "AND (s.status = 'RUNNING' "
            + "     OR (s.status = 'DISPATCHING' AND o.status = 1))")
    int countActiveCapabilityRuns(@Param("action") String action);
}
