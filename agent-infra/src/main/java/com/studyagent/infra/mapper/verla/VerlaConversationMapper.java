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

    @Select("SELECT * FROM verla_conversations "
            + "WHERE user_id = #{userId} AND status <> 'deleted' "
            + "ORDER BY last_message_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<VerlaConversationEntity> selectByUserPaged(@Param("userId") String userId,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    /**
     * 自增 version + 同步刷新 last_message_at / last_turn_id / turn_count（按需）
     */
    @Update("UPDATE verla_conversations "
            + "SET version = version + 1, "
            + "    last_turn_id = #{turnId}, "
            + "    last_message_at = NOW(), "
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
