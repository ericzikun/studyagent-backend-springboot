package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaEventCursorEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface VerlaEventCursorMapper extends BaseMapper<VerlaEventCursorEntity> {

    @Select("SELECT * FROM verla_event_cursor WHERE session_id = #{sessionId} FOR UPDATE")
    VerlaEventCursorEntity selectForUpdate(@Param("sessionId") Long sessionId);

    @Update("UPDATE verla_event_cursor SET next_expected_seq = #{nextExpected}, "
            + "last_processed_seq = #{lastProcessed}, updated_at = #{ts} "
            + "WHERE session_id = #{sessionId}")
    int advance(@Param("sessionId") Long sessionId,
                @Param("nextExpected") Long nextExpected,
                @Param("lastProcessed") Long lastProcessed,
                @Param("ts") LocalDateTime ts);
}
