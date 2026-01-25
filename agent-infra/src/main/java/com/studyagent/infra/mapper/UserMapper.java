package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.UserProfileEntity;

/**
 * 用户Mapper接口
 * 注意: 不需要 @Mapper 注解，@MapperScan 会扫描所有接口
 */
public interface UserMapper extends BaseMapper<UserProfileEntity> {
}

