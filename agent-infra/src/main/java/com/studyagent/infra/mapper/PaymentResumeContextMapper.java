package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.PaymentResumeContextEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface PaymentResumeContextMapper extends BaseMapper<PaymentResumeContextEntity> {

    @Select("SELECT * FROM payment_resume_context WHERE resume_token = #{resumeToken} LIMIT 1 FOR UPDATE")
    PaymentResumeContextEntity selectByResumeTokenForUpdate(@Param("resumeToken") String resumeToken);

    @Update("UPDATE payment_resume_context SET status = 'resumed', resumed_at = #{resumedAt}, updated_at = #{resumedAt} "
            + "WHERE id = #{id} AND status = 'pending'")
    int markResumed(@Param("id") Long id, @Param("resumedAt") LocalDateTime resumedAt);
}
