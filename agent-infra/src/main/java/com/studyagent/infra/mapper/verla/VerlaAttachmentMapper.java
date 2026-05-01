package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaAttachmentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaAttachmentMapper extends BaseMapper<VerlaAttachmentEntity> {

    @Select("SELECT * FROM verla_attachments WHERE object_id = #{oid} LIMIT 1")
    VerlaAttachmentEntity selectByObjectId(@Param("oid") String objectId);

    @Select("SELECT * FROM verla_attachments WHERE conversation_id = #{cid} "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<VerlaAttachmentEntity> selectByConversation(@Param("cid") Long conversationId,
                                                     @Param("limit") int limit);

    @Select("SELECT * FROM verla_attachments WHERE turn_id = #{tid} "
            + "ORDER BY created_at ASC, id ASC")
    List<VerlaAttachmentEntity> selectByTurn(@Param("tid") Long turnId);
}
