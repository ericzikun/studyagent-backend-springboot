package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaEditorPreviewEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaEditorPreviewMapper extends BaseMapper<VerlaEditorPreviewEntity> {

    @Select("SELECT * FROM verla_editor_previews " +
            "WHERE conversation_id = #{conversationId} " +
            "ORDER BY updated_at DESC " +
            "LIMIT 3")
    List<VerlaEditorPreviewEntity> selectByConversationId(
            @Param("conversationId") Long conversationId);

    @Select("<script>" +
            "SELECT * FROM verla_editor_previews " +
            "WHERE conversation_id IN " +
            "<foreach item='id' collection='conversationIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            " ORDER BY updated_at DESC" +
            "</script>")
    List<VerlaEditorPreviewEntity> selectByConversationIds(
            @Param("conversationIds") List<Long> conversationIds);
}
