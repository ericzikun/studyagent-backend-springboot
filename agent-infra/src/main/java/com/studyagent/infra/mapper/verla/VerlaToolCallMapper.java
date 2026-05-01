package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaToolCallEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaToolCallMapper extends BaseMapper<VerlaToolCallEntity> {

    @Select("SELECT * FROM verla_tool_calls WHERE tool_call_id = #{cid} LIMIT 1")
    VerlaToolCallEntity selectByCallId(@Param("cid") String toolCallId);

    @Select("SELECT * FROM verla_tool_calls WHERE turn_id = #{tid} "
            + "ORDER BY started_at DESC, id DESC LIMIT #{limit}")
    List<VerlaToolCallEntity> selectByTurn(@Param("tid") Long turnId,
                                           @Param("limit") int limit);

    @Select("SELECT * FROM verla_tool_calls WHERE session_id = #{sid} "
            + "ORDER BY started_at DESC, id DESC LIMIT #{limit}")
    List<VerlaToolCallEntity> selectBySession(@Param("sid") Long sessionId,
                                              @Param("limit") int limit);

    @Select("SELECT * FROM verla_tool_calls "
            + "WHERE conversation_id = #{cid} AND visibility = 'USER_VISIBLE' "
            + "ORDER BY started_at DESC, id DESC LIMIT #{limit}")
    List<VerlaToolCallEntity> selectVisibleByConversation(@Param("cid") Long conversationId,
                                                          @Param("limit") int limit);
}
