package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.FollowupEditUsageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface FollowupEditUsageMapper extends BaseMapper<FollowupEditUsageEntity> {

    @Select("SELECT * FROM verla_followup_edit_usages WHERE user_message_id = #{userMessageId} LIMIT 1")
    FollowupEditUsageEntity selectByUserMessageId(@Param("userMessageId") Long userMessageId);

    @Select("SELECT * FROM verla_followup_edit_usages "
            + "WHERE assignment_chat_session_id = #{assignmentChatSessionId} LIMIT 1")
    FollowupEditUsageEntity selectByAssignmentChatSessionId(@Param("assignmentChatSessionId") Long assignmentChatSessionId);

    @Select("SELECT COUNT(*) FROM verla_followup_edit_usages "
            + "WHERE assignment_session_id = #{assignmentSessionId} "
            + "AND state IN ('RESERVED', 'COMPLETED')")
    Long countActiveByAssignmentSessionId(@Param("assignmentSessionId") Long assignmentSessionId);

    @Update("UPDATE verla_followup_edit_usages "
            + "SET state = #{state}, "
            + "assignment_chat_session_id = #{assignmentChatSessionId}, "
            + "release_reason = #{releaseReason} "
            + "WHERE user_message_id = #{userMessageId}")
    int updateState(@Param("userMessageId") Long userMessageId,
                    @Param("state") String state,
                    @Param("assignmentChatSessionId") Long assignmentChatSessionId,
                    @Param("releaseReason") String releaseReason);
}
