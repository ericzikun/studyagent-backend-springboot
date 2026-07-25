package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.OpsInternalUserEntity;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Ops internal users mapper. Scanned by {@code @MapperScan}.
 */
public interface OpsInternalUserMapper extends BaseMapper<OpsInternalUserEntity> {

    @Select("SELECT clerk_user_id FROM ops_internal_users WHERE status = 'active'")
    List<String> selectActiveClerkUserIds();
}
