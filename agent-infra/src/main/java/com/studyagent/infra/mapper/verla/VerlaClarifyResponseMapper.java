package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaClarifyResponseEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaClarifyResponseMapper extends BaseMapper<VerlaClarifyResponseEntity> {

    @Select("SELECT * FROM verla_clarify_responses WHERE response_uid = #{uid} LIMIT 1")
    VerlaClarifyResponseEntity selectByResponseUid(@Param("uid") String responseUid);

    @Select("SELECT * FROM verla_clarify_responses WHERE form_id = #{fid} "
            + "ORDER BY submitted_at ASC, id ASC")
    List<VerlaClarifyResponseEntity> selectByFormId(@Param("fid") String formId);
}
