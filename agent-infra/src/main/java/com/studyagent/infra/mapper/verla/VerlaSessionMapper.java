package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaSessionMapper extends BaseMapper<VerlaSessionEntity> {

    @Select("SELECT * FROM verla_sessions WHERE id = #{id} FOR UPDATE")
    VerlaSessionEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM verla_sessions "
            + "WHERE turn_id = #{turnId} ORDER BY id ASC")
    List<VerlaSessionEntity> selectByTurn(@Param("turnId") Long turnId);

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
}
