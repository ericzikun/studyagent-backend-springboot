package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaArtifactEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaArtifactMapper extends BaseMapper<VerlaArtifactEntity> {

    String ARTIFACT_METADATA_COLUMNS =
            "id, artifact_uid, conversation_id, turn_id, session_id, source_message_id, "
                    + "source_object_id, kind, mime, summary, content_ref, status, size_bytes, "
                    + "version, meta_json, updated_at";

    @Select("SELECT " + ARTIFACT_METADATA_COLUMNS + " FROM verla_artifacts WHERE conversation_id = #{cid} "
            + "ORDER BY updated_at DESC, id DESC")
    List<VerlaArtifactEntity> selectByConversation(@Param("cid") Long conversationId);

    @Select("SELECT " + ARTIFACT_METADATA_COLUMNS + " FROM verla_artifacts WHERE session_id = #{sid} "
            + "ORDER BY updated_at DESC, id DESC")
    List<VerlaArtifactEntity> selectBySession(@Param("sid") Long sessionId);

    @Select("SELECT * FROM verla_artifacts WHERE artifact_uid = #{uid} LIMIT 1")
    VerlaArtifactEntity selectByUid(@Param("uid") String artifactUid);

    @Select("<script>" +
            "SELECT " + ARTIFACT_METADATA_COLUMNS + " FROM verla_artifacts " +
            "WHERE conversation_id IN " +
            "<foreach item='id' collection='conversationIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            " ORDER BY updated_at DESC" +
            "</script>")
    List<VerlaArtifactEntity> selectByConversationIds(
            @Param("conversationIds") List<Long> conversationIds);
}
