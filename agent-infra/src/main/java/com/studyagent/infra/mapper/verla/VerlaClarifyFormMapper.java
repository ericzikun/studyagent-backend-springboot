package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaClarifyFormEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VerlaClarifyFormMapper extends BaseMapper<VerlaClarifyFormEntity> {

    @Select("SELECT * FROM verla_clarify_forms WHERE form_id = #{fid} LIMIT 1")
    VerlaClarifyFormEntity selectByFormId(@Param("fid") String formId);

    @Select("SELECT * FROM verla_clarify_forms WHERE conversation_id = #{cid} AND status = 'OPEN' "
            + "ORDER BY created_at ASC, id ASC")
    List<VerlaClarifyFormEntity> selectOpenByConversation(@Param("cid") Long conversationId);

    @Update("UPDATE verla_clarify_forms SET status = 'SUBMITTED', "
            + "submitted_at = #{ts}, submitted_response_id = #{rid}, updated_at = #{ts} "
            + "WHERE form_id = #{fid} AND status = 'OPEN'")
    int markSubmitted(@Param("fid") String formId,
                      @Param("rid") Long responseId,
                      @Param("ts") LocalDateTime ts);

    @Update("UPDATE verla_clarify_forms SET status = #{newStatus}, updated_at = #{ts} "
            + "WHERE form_id = #{fid} AND status = 'OPEN'")
    int markStatus(@Param("fid") String formId,
                   @Param("newStatus") String newStatus,
                   @Param("ts") LocalDateTime ts);
}
