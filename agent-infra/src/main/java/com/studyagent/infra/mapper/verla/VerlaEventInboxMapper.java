package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaEventInboxEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VerlaEventInboxMapper extends BaseMapper<VerlaEventInboxEntity> {

    @Select("SELECT * FROM verla_event_inbox WHERE message_id = #{messageId}")
    VerlaEventInboxEntity selectByMessageId(@Param("messageId") String messageId);

    @Select("SELECT * FROM verla_event_inbox "
            + "WHERE session_id = #{sessionId} AND event_seq = #{seq} AND status = 'READY' "
            + "LIMIT 1")
    VerlaEventInboxEntity selectReady(@Param("sessionId") Long sessionId,
                                       @Param("seq") Long seq);

    @Update("UPDATE verla_event_inbox SET status = 'PROCESSED', processed_at = #{ts} "
            + "WHERE id = #{id} AND status = 'READY'")
    int markProcessed(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    @Update("UPDATE verla_event_inbox SET status = 'SKIPPED', error_message = #{msg}, "
            + "processed_at = #{ts} WHERE id = #{id} AND status = 'READY'")
    int markSkipped(@Param("id") Long id, @Param("msg") String msg, @Param("ts") LocalDateTime ts);

    @Update("UPDATE verla_event_inbox SET status = 'FAILED', error_message = #{msg}, "
            + "processed_at = #{ts} WHERE id = #{id} AND status = 'READY'")
    int markFailed(@Param("id") Long id, @Param("msg") String msg, @Param("ts") LocalDateTime ts);

    /**
     * 找出"有 READY 行 + 数量 > 0"的 session_id。
     * MVP 兜底实现：任何含 READY 的 session 都视为 stuck 候选；
     * 业务侧再调 cursor 比较 next_expected_seq 真正决定是否推进。
     */
    @Select("SELECT DISTINCT session_id FROM verla_event_inbox "
            + "WHERE status = 'READY' "
            + "ORDER BY session_id ASC LIMIT #{limit}")
    List<Long> selectStuckSessions(@Param("limit") int limit);

    /**
     * SSE 补发：拉某 conversation 自 afterId 之后所有已 PROCESSED 的事件，按 id 升序。
     */
    @Select("SELECT * FROM verla_event_inbox "
            + "WHERE conversation_id = #{cid} AND id > #{afterId} AND status = 'PROCESSED' "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<VerlaEventInboxEntity> selectReplay(@Param("cid") Long conversationId,
                                             @Param("afterId") Long afterId,
                                             @Param("limit") int limit);

    @Select("SELECT * FROM verla_event_inbox "
            + "WHERE conversation_id = #{cid} AND status = 'PROCESSED' "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<VerlaEventInboxEntity> selectRecentProcessed(@Param("cid") Long conversationId,
                                                      @Param("limit") int limit);

    @Select("SELECT * FROM verla_event_inbox "
            + "WHERE session_id = #{sessionId} AND status = 'PROCESSED' "
            + "ORDER BY id DESC LIMIT 1")
    VerlaEventInboxEntity selectLatestProcessedBySession(@Param("sessionId") Long sessionId);
}
