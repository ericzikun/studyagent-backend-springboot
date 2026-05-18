package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VerlaMessageMapper extends BaseMapper<VerlaMessageEntity> {

    /**
     * 游标分页（id < cursor，倒序）
     */
    @Select("<script>"
            + "SELECT * FROM verla_messages "
            + "WHERE conversation_id = #{conversationId} "
            + "<if test='cursor != null'> AND id &lt; #{cursor} </if> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<VerlaMessageEntity> selectByCursor(@Param("conversationId") Long conversationId,
                                            @Param("cursor") Long cursor,
                                            @Param("limit") int limit);
}
