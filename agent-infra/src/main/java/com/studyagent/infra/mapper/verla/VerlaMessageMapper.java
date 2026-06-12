package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaMessageMapper extends BaseMapper<VerlaMessageEntity> {

    /**
     * 主对话消息分页。文件对话 / 作业追问消息写入同表，但用 meta_json.scene
     * （FILE_CHAT / ASSIGNMENT_CHAT）隔离，不进入主聊天历史。
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')), '') "
            + "NOT IN ('FILE_CHAT', 'ASSIGNMENT_CHAT') "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectByCursor(@Param("conversationId") Long conversationId,
                                            @Param("cursor") Long cursor,
                                            @Param("limit") int limit);

    /**
     * 作业追问（Chat With Assignment）历史分页：scene=ASSIGNMENT_CHAT，键到 conversation。
     * 左栏可见、可回看，但与主对话隔离（主路径 selectByCursor 已排除）。
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')) = 'ASSIGNMENT_CHAT' "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectAssignmentChatByCursor(@Param("conversationId") Long conversationId,
                                                          @Param("cursor") Long cursor,
                                                          @Param("limit") int limit);

    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.scene')) = 'FILE_CHAT' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(meta_json, '$.objectId')) = #{objectId} "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectFileChatByCursor(@Param("conversationId") Long conversationId,
                                                    @Param("objectId") String objectId,
                                                    @Param("cursor") Long cursor,
                                                    @Param("limit") int limit);
}
