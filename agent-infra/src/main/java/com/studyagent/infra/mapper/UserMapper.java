package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.UserProfileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户Mapper接口
 * 注意: 不需要 @Mapper 注解，@MapperScan 会扫描所有接口
 */
public interface UserMapper extends BaseMapper<UserProfileEntity> {

    @Select("<script>"
            + "SELECT * FROM user_profiles WHERE clerk_user_id IN "
            + "<foreach collection='clerkUserIds' item='id' open='(' separator=',' close=')'>"
            + "#{id}"
            + "</foreach>"
            + "</script>")
    List<UserProfileEntity> selectByClerkUserIds(@Param("clerkUserIds") List<String> clerkUserIds);
}

