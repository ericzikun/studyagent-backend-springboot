package com.studyagent.infra.mapper.mq;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.mq.MqOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MQ 事务发件箱 Mapper 接口
 */
@Mapper
public interface MqOutboxMapper extends BaseMapper<MqOutboxEntity> {
}
