package com.studyagent.infra.mapper.demo.note;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.demo.note.DemoNoteFeedbackEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DemoNoteFeedbackMapper extends BaseMapper<DemoNoteFeedbackEntity> {
}
