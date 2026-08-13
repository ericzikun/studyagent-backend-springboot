package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaAttachmentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface VerlaAttachmentMapper extends BaseMapper<VerlaAttachmentEntity> {

    @Select("SELECT * FROM verla_attachments WHERE object_id = #{oid} LIMIT 1")
    VerlaAttachmentEntity selectByObjectId(@Param("oid") String objectId);

    @Select("SELECT * FROM verla_attachments WHERE conversation_id = #{cid} "
            + "AND deleted_at IS NULL "
            + "AND (attachment_origin IS NULL OR attachment_origin <> 'AGENT_OUTPUT') "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<VerlaAttachmentEntity> selectByConversation(@Param("cid") Long conversationId,
                                                     @Param("limit") int limit);

    /**
     * 批量取多个 conversation 的用户上传附件（USER_UPLOAD 或历史 NULL origin、未删除），
     * 按 created_at 升序。admin 列表用。
     */
    @Select("<script>"
            + "SELECT * FROM verla_attachments WHERE conversation_id IN "
            + "<foreach item='id' collection='conversationIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND deleted_at IS NULL "
            + "AND (attachment_origin IS NULL OR attachment_origin = 'USER_UPLOAD') "
            + "ORDER BY created_at ASC, id ASC"
            + "</script>")
    List<VerlaAttachmentEntity> selectUserUploadsByConversationIds(
            @Param("conversationIds") List<Long> conversationIds);

    @Select("SELECT * FROM verla_attachments WHERE turn_id = #{tid} "
            + "AND deleted_at IS NULL "
            + "ORDER BY created_at ASC, id ASC")
    List<VerlaAttachmentEntity> selectByTurn(@Param("tid") Long turnId);

    @Select("SELECT COUNT(*) FROM verla_attachments "
            + "WHERE conversation_id = #{conversationId} "
            + "AND deleted_at IS NULL "
            + "AND (attachment_origin IS NULL OR attachment_origin = 'USER_UPLOAD') "
            + "AND (storage_uri IS NULL OR storage_uri NOT LIKE 'pending://%' OR created_at >= #{pendingCutoff}) "
            + "FOR UPDATE")
    Long countActiveUserUploadsForConversation(@Param("conversationId") Long conversationId,
                                               @Param("pendingCutoff") java.time.LocalDateTime pendingCutoff);

    @Select("SELECT * FROM verla_attachments "
            + "WHERE object_id = #{objectId} "
            + "AND user_id = #{clerkUserId} "
            + "AND deleted_at IS NULL "
            + "AND (attachment_origin IS NULL OR attachment_origin = 'USER_UPLOAD') "
            + "LIMIT 1 FOR UPDATE")
    VerlaAttachmentEntity selectActiveUserUploadForUpdate(@Param("clerkUserId") String clerkUserId,
                                                          @Param("objectId") String objectId);

    @Update("UPDATE verla_attachments "
            + "SET deleted_at = #{deletedAt}, updated_at = #{deletedAt} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id, @Param("deletedAt") java.time.LocalDateTime deletedAt);

    @Update("UPDATE verla_attachments "
            + "SET status = 'FAILED', parse_error = #{reason}, updated_at = NOW() "
            + "WHERE attachment_origin = 'AGENT_OUTPUT' "
            + "AND status = 'UPLOADED' "
            + "AND deleted_at IS NULL "
            + "AND (storage_uri IS NULL OR storage_uri LIKE 'pending://%') "
            + "AND created_at < #{cutoff} "
            + "ORDER BY id ASC LIMIT #{batchSize}")
    int markStaleUploadedAgentOutputsFailed(@Param("cutoff") java.time.LocalDateTime cutoff,
                                            @Param("batchSize") int batchSize,
                                            @Param("reason") String reason);
}
