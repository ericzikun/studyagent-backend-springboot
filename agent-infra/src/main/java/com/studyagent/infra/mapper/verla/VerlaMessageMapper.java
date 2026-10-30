package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaMessageMapper extends BaseMapper<VerlaMessageEntity> {

    /**
     * 主对话消息分页。文件对话 / 作业追问 / 白板教学消息写入同表，但用 meta_json.scene
     * （FILE_CHAT / ASSIGNMENT_CHAT / VIDEO_TUTOR）隔离，不进入主聊天历史。
     * <p>
     * ⚠️ 只在这里排除 {@code VIDEO_TUTOR}；{@link #selectFirstUserQueryByConversationIds}
     * 那条 admin 查询**故意保留** —— 管理员列表需要看到白板会话的第一个提问。
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') "
            + "NOT IN ('FILE_CHAT', 'ASSIGNMENT_CHAT', 'VIDEO_TUTOR') "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectByCursor(@Param("conversationId") Long conversationId,
                                            @Param("cursor") Long cursor,
                                            @Param("limit") int limit);

    /**
     * 白板教学（Video Tutor）历史分页：scene=VIDEO_TUTOR，键到 conversation。
     * 左栏可见、可回看，但与主对话隔离（主路径 selectByCursor 已排除）。
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') = 'VIDEO_TUTOR' "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectVideoTutorByCursor(@Param("conversationId") Long conversationId,
                                                     @Param("cursor") Long cursor,
                                                     @Param("limit") int limit);

    /**
     * 作业追问（Chat With Assignment）历史分页：scene=ASSIGNMENT_CHAT，键到 conversation。
     * 左栏可见、可回看，但与主对话隔离（主路径 selectByCursor 已排除）。
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') = 'ASSIGNMENT_CHAT' "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectAssignmentChatByCursor(@Param("conversationId") Long conversationId,
                                                          @Param("cursor") Long cursor,
                                                          @Param("limit") int limit);

    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') = 'FILE_CHAT' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.objectId')) = #{objectId} "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectFileChatByCursor(@Param("conversationId") Long conversationId,
                                                    @Param("objectId") String objectId,
                                                    @Param("cursor") Long cursor,
                                                    @Param("limit") int limit);

    @Select("SELECT * FROM verla_messages "
            + "WHERE turn_id = #{turnId} "
            + "AND role = #{role} "
            + "AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') = #{scene} "
            + "ORDER BY id ASC LIMIT 1")
    VerlaMessageEntity selectByTurnRoleScene(@Param("turnId") Long turnId,
                                             @Param("role") String role,
                                             @Param("scene") String scene);

    /**
     * 同一 turn 同一 formId 的作业澄清提交，finalize 幂等去重用。
     * <p>
     * 必须是加锁读：finalize 事务在拿到 turn 行锁前已执行过普通 SELECT，
     * REPEATABLE READ 快照可能早于并发事务的提交；FOR UPDATE 才能读到最新已提交行。
     */
    @Select("SELECT * FROM verla_messages "
            + "WHERE turn_id = #{turnId} "
            + "AND role = 'user' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$.phase')) = 'assignment_clarify' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$.formId')) = #{formId} "
            + "ORDER BY id ASC LIMIT 1 FOR UPDATE")
    VerlaMessageEntity selectUserClarifyByTurnAndFormIdForUpdate(
            @Param("turnId") Long turnId,
            @Param("formId") String formId);

    /**
     * 同一 turn 的作业澄清提交（不依赖 formId），finalize 幂等的最终兜底。
     * <p>
     * 前端 formId 在实时 SSE / 历史恢复两条路径上可能不一致，按 turn 兜底可避免
     * 同一张表单因 formId 不同而重复提交。finalize 消息必定写 requirementForm；
     * continue（deep_understanding / setup）消息没有该键，不会被误命中。
     */
    @Select("SELECT * FROM verla_messages "
            + "WHERE turn_id = #{turnId} "
            + "AND role = 'user' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$.phase')) = 'assignment_clarify' "
            + "AND JSON_CONTAINS_PATH(blocks_json, 'one', '$.requirementForm') "
            + "ORDER BY id ASC LIMIT 1 FOR UPDATE")
    VerlaMessageEntity selectUserFinalizeByTurnForUpdate(@Param("turnId") Long turnId);

    /**
     * 批量取每个 conversation 第一条 user 主对话消息（scene 过滤与 selectByCursor 一致）。
     * admin 列表用；返回 {@code m.*}，供仓储层拼接 text_content + blocks_json。
     */
    @Select("<script>"
            + "SELECT m.* FROM verla_messages m "
            + "JOIN (SELECT conversation_id, MIN(id) AS firstId FROM verla_messages "
            + "      WHERE conversation_id IN "
            + "      <foreach item='id' collection='conversationIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "      AND role = 'user' "
            + "      AND COALESCE(scene, JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') "
            + "          NOT IN ('FILE_CHAT', 'ASSIGNMENT_CHAT') "
            + "      GROUP BY conversation_id) f "
            + "  ON f.conversation_id = m.conversation_id AND f.firstId = m.id"
            + "</script>")
    List<VerlaMessageEntity> selectFirstUserQueryByConversationIds(
            @Param("conversationIds") List<Long> conversationIds);
}
