package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaTurnEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaTurnMapper extends BaseMapper<VerlaTurnEntity> {

    @Select("SELECT * FROM verla_turns "
            + "WHERE conversation_id = #{conversationId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<VerlaTurnEntity> selectRecentByConversation(@Param("conversationId") Long conversationId,
                                                     @Param("limit") int limit);

    @Select("SELECT * FROM verla_turns WHERE id = #{id} FOR UPDATE")
    VerlaTurnEntity selectByIdForUpdate(@Param("id") Long id);
}
