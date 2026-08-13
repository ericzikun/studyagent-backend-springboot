package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.AiWritingHumanizerResultEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiWritingHumanizerResultMapper extends BaseMapper<AiWritingHumanizerResultEntity> {

    @Insert("INSERT IGNORE INTO ai_writing_humanizer_results "
            + "(clerk_user_id, conversation_id, session_id, artifact_uid, result_hash, result_text, created_at) "
            + "VALUES (#{clerkUserId}, #{conversationId}, #{sessionId}, #{artifactUid}, "
            + "#{resultHash}, #{resultText}, #{createdAt})")
    int insertIgnore(AiWritingHumanizerResultEntity entity);

    @Select("SELECT 1 FROM ai_writing_humanizer_results "
            + "WHERE clerk_user_id = #{clerkUserId} AND result_hash = #{resultHash} LIMIT 1")
    Integer existsByUserAndHash(@Param("clerkUserId") String clerkUserId,
                                @Param("resultHash") String resultHash);

    @Select("SELECT id, clerk_user_id, conversation_id, session_id, artifact_uid, "
            + "result_hash, result_text, created_at "
            + "FROM ai_writing_humanizer_results "
            + "WHERE clerk_user_id = #{clerkUserId} "
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit}")
    List<AiWritingHumanizerResultEntity> selectRecentByUser(@Param("clerkUserId") String clerkUserId,
                                                            @Param("limit") int limit);
}
