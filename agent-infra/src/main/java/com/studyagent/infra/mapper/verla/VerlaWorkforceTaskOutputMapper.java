package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaWorkforceTaskOutputEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaWorkforceTaskOutputMapper extends BaseMapper<VerlaWorkforceTaskOutputEntity> {

    @Select("SELECT * FROM verla_workforce_task_outputs WHERE session_id = #{sid} AND node_id = #{nid} LIMIT 1")
    VerlaWorkforceTaskOutputEntity selectBySessionAndNode(@Param("sid") Long sessionId,
                                                          @Param("nid") String nodeId);

    @Select("SELECT * FROM verla_workforce_task_outputs WHERE session_id = #{sid}")
    List<VerlaWorkforceTaskOutputEntity> selectBySession(@Param("sid") Long sessionId);
}
