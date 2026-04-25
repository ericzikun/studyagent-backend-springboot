package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaArtifactMapper extends BaseMapper<VerlaArtifactEntity> {

    @Select("SELECT * FROM verla_artifacts WHERE conversation_id = #{cid} "
            + "ORDER BY updated_at DESC, id DESC")
    List<VerlaArtifactEntity> selectByConversation(@Param("cid") Long conversationId);

    @Select("SELECT * FROM verla_artifacts WHERE session_id = #{sid} "
            + "ORDER BY updated_at DESC, id DESC")
    List<VerlaArtifactEntity> selectBySession(@Param("sid") Long sessionId);
}
