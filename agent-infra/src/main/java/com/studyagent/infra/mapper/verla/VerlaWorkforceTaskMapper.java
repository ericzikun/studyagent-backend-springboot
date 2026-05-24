package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaWorkforceTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaWorkforceTaskMapper extends BaseMapper<VerlaWorkforceTaskEntity> {

    @Select("SELECT * FROM verla_workforce_tasks WHERE session_id = #{sid} AND node_id = #{nid} LIMIT 1")
    VerlaWorkforceTaskEntity selectBySessionAndNode(@Param("sid") Long sessionId,
                                                    @Param("nid") String nodeId);

    @Select("SELECT * FROM verla_workforce_tasks WHERE session_id = #{sid} ORDER BY sort_order ASC, id ASC")
    List<VerlaWorkforceTaskEntity> selectBySession(@Param("sid") Long sessionId);

    @Select("SELECT * FROM verla_workforce_tasks WHERE conversation_id = #{cid} ORDER BY session_id ASC, sort_order ASC")
    List<VerlaWorkforceTaskEntity> selectByConversation(@Param("cid") Long conversationId);
}
