package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaConversationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * verla_conversations Mapper
 * <p>
 * 由 @MapperScan("com.studyagent.infra.mapper") 通过子包扫描时不会被覆盖，
 * 主启动类已扩展为 com.studyagent.infra.mapper.**（如未扩展请在 PR-6 调整）。
 */
public interface VerlaConversationMapper extends BaseMapper<VerlaConversationEntity> {

    /**
     * 会话列表（支持 Dashboard segment / status 过滤），与非过滤语义兼容：
     * {@code segment == null && conversationStatus == null} 时等价于旧「全部未删除」列表。
     */
    @Select("<script>"
            + "SELECT * FROM verla_conversations WHERE user_id = #{userId} AND status &lt;&gt; 'deleted' "
            + "<if test='conversationStatus != null'> AND status = #{conversationStatus} </if>"
            + "<if test='segment != null'>"
            + "<choose>"
            + "<when test='segment == \"assignment\"'> AND (primary_intent IS NULL OR primary_intent = '' "
            + "OR primary_intent IN ('ASSIGNMENT','CREATE_ASSIGNMENT')) </when>"
            + "<when test='segment == \"learning\"'> AND primary_intent IN "
            + "('MATERIALS','LEARNING','FLASHCARDS','QUIZZES','STUDY_GUIDE','CHEAT_SHEET') </when>"
            + "<when test='segment == \"ai_writing\"'> AND primary_intent IN ('AI_DETECTION','AI_HUMANIZER') </when>"
            + "<when test='segment == \"ai_detection\"'> AND primary_intent = 'AI_DETECTION' </when>"
            + "<when test='segment == \"ai_humanizer\"'> AND primary_intent = 'AI_HUMANIZER' </when>"
            + "</choose>"
            + "</if>"
            + " ORDER BY COALESCE(last_active_at, last_message_at, created_at) DESC, id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<VerlaConversationEntity> selectByUserFilteredPaged(@Param("userId") String userId,
                                                            @Param("segment") String segment,
                                                            @Param("conversationStatus") String conversationStatus,
                                                            @Param("limit") int limit,
                                                            @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM verla_conversations WHERE user_id = #{userId} AND status &lt;&gt; 'deleted' "
            + "<if test='conversationStatus != null'> AND status = #{conversationStatus} </if>"
            + "<if test='segment != null'>"
            + "<choose>"
            + "<when test='segment == \"assignment\"'> AND (primary_intent IS NULL OR primary_intent = '' "
            + "OR primary_intent IN ('ASSIGNMENT','CREATE_ASSIGNMENT')) </when>"
            + "<when test='segment == \"learning\"'> AND primary_intent IN "
            + "('MATERIALS','LEARNING','FLASHCARDS','QUIZZES','STUDY_GUIDE','CHEAT_SHEET') </when>"
            + "<when test='segment == \"ai_writing\"'> AND primary_intent IN ('AI_DETECTION','AI_HUMANIZER') </when>"
            + "<when test='segment == \"ai_detection\"'> AND primary_intent = 'AI_DETECTION' </when>"
            + "<when test='segment == \"ai_humanizer\"'> AND primary_intent = 'AI_HUMANIZER' </when>"
            + "</choose>"
            + "</if>"
            + "</script>")
    long countByUserFiltered(@Param("userId") String userId,
                             @Param("segment") String segment,
                             @Param("conversationStatus") String conversationStatus);

    /**
     * 关键词模糊搜索（标题 + 消息正文），可选 segment / status 过滤。
     * {@code keyword} 已在 Java 侧转义 LIKE 通配符。
     */
    @Select("<script>"
            + "SELECT DISTINCT c.* FROM verla_conversations c "
            + "WHERE c.user_id = #{userId} AND c.status &lt;&gt; 'deleted' "
            + "<if test='conversationStatus != null'> AND c.status = #{conversationStatus} </if>"
            + "<if test='segment != null'>"
            + "<choose>"
            + "<when test='segment == \"assignment\"'> AND (c.primary_intent IS NULL OR c.primary_intent = '' "
            + "OR c.primary_intent IN ('ASSIGNMENT','CREATE_ASSIGNMENT')) </when>"
            + "<when test='segment == \"learning\"'> AND c.primary_intent IN "
            + "('MATERIALS','LEARNING','FLASHCARDS','QUIZZES','STUDY_GUIDE','CHEAT_SHEET') </when>"
            + "<when test='segment == \"ai_writing\"'> AND c.primary_intent IN ('AI_DETECTION','AI_HUMANIZER') </when>"
            + "<when test='segment == \"ai_detection\"'> AND c.primary_intent = 'AI_DETECTION' </when>"
            + "<when test='segment == \"ai_humanizer\"'> AND c.primary_intent = 'AI_HUMANIZER' </when>"
            + "</choose>"
            + "</if>"
            + " AND (c.title LIKE CONCAT('%', #{keyword}, '%') "
            + "OR EXISTS (SELECT 1 FROM verla_messages m "
            + "WHERE m.conversation_id = c.id AND m.text_content LIKE CONCAT('%', #{keyword}, '%'))) "
            + "ORDER BY COALESCE(c.last_active_at, c.last_message_at, c.created_at) DESC, c.id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<VerlaConversationEntity> searchByUserKeywordPaged(@Param("userId") String userId,
                                                           @Param("keyword") String keyword,
                                                           @Param("segment") String segment,
                                                           @Param("conversationStatus") String conversationStatus,
                                                           @Param("limit") int limit,
                                                           @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(DISTINCT c.id) FROM verla_conversations c "
            + "WHERE c.user_id = #{userId} AND c.status &lt;&gt; 'deleted' "
            + "<if test='conversationStatus != null'> AND c.status = #{conversationStatus} </if>"
            + "<if test='segment != null'>"
            + "<choose>"
            + "<when test='segment == \"assignment\"'> AND (c.primary_intent IS NULL OR c.primary_intent = '' "
            + "OR c.primary_intent IN ('ASSIGNMENT','CREATE_ASSIGNMENT')) </when>"
            + "<when test='segment == \"learning\"'> AND c.primary_intent IN "
            + "('MATERIALS','LEARNING','FLASHCARDS','QUIZZES','STUDY_GUIDE','CHEAT_SHEET') </when>"
            + "<when test='segment == \"ai_writing\"'> AND c.primary_intent IN ('AI_DETECTION','AI_HUMANIZER') </when>"
            + "<when test='segment == \"ai_detection\"'> AND c.primary_intent = 'AI_DETECTION' </when>"
            + "<when test='segment == \"ai_humanizer\"'> AND c.primary_intent = 'AI_HUMANIZER' </when>"
            + "</choose>"
            + "</if>"
            + " AND (c.title LIKE CONCAT('%', #{keyword}, '%') "
            + "OR EXISTS (SELECT 1 FROM verla_messages m "
            + "WHERE m.conversation_id = c.id AND m.text_content LIKE CONCAT('%', #{keyword}, '%'))) "
            + "</script>")
    long countByUserKeyword(@Param("userId") String userId,
                            @Param("keyword") String keyword,
                            @Param("segment") String segment,
                            @Param("conversationStatus") String conversationStatus);

    /**
     * 自增 version + 同步刷新 last_message_at / last_active_at / last_turn_id / turn_count（按需）
     */
    @Update("UPDATE verla_conversations "
            + "SET version = version + 1, "
            + "    last_turn_id = #{turnId}, "
            + "    last_message_at = NOW(), "
            + "    last_active_at = NOW(), "
            + "    turn_count = turn_count + 1, "
            + "    updated_at = NOW() "
            + "WHERE id = #{id}")
    int touchOnNewTurn(@Param("id") Long id, @Param("turnId") Long turnId);

    /**
     * 仅自增 version（用于 turn / message / artifact 写入后让缓存版本前进）
     */
    @Update("UPDATE verla_conversations SET version = version + 1, updated_at = NOW() WHERE id = #{id}")
    int incrementVersion(@Param("id") Long id);

    /**
     * 仅刷新改动时间 last_active_at = NOW()（用户点击任务 / 编辑内容更新时调用）。
     * 不动 version / turn_count，避免触发缓存失效与重复计数。
     */
    @Update("UPDATE verla_conversations SET last_active_at = NOW(), updated_at = NOW() WHERE id = #{id}")
    int touchActiveAt(@Param("id") Long id);

    /**
     * 更新 AI 生成的对话标题（title）。
     */
    @Update("UPDATE verla_conversations SET title = #{title}, updated_at = NOW() WHERE id = #{id}")
    int updateTitle(@Param("id") Long id, @Param("title") String title);

    /**
     * 把当前 version 当作期望值乐观更新
     */
    default int updateStatusOptimistic(Long id, String newStatus, Long expectedVersion) {
        UpdateWrapper<VerlaConversationEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .eq("version", expectedVersion)
                .set("status", newStatus)
                .set("version", expectedVersion + 1);
        return update(null, wrapper);
    }
}
