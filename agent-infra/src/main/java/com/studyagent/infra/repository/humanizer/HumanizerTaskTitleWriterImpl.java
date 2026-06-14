package com.studyagent.infra.repository.humanizer;

import com.studyagent.infra.mapper.HumanizerTaskMapper;
import com.studyagent.service.domain.humanizer.HumanizerTaskTitleWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link HumanizerTaskTitleWriter} 的 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
public class HumanizerTaskTitleWriterImpl implements HumanizerTaskTitleWriter {

    private final HumanizerTaskMapper mapper;

    @Override
    public int updateTaskName(Long taskId, String taskName) {
        if (taskId == null || taskName == null || taskName.isBlank()) {
            return 0;
        }
        return mapper.updateTaskName(taskId, taskName.trim());
    }
}
