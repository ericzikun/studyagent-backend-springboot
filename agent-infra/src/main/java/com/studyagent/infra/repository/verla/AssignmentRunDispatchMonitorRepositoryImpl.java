package com.studyagent.infra.repository.verla;

import com.studyagent.infra.mapper.verla.AssignmentRunDispatchMonitorMapper;
import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchTaskQueryRow;
import com.studyagent.service.domain.verla.repo.AssignmentRunDispatchMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AssignmentRunDispatchMonitorRepositoryImpl
        implements AssignmentRunDispatchMonitorRepository {

    private final AssignmentRunDispatchMonitorMapper mapper;

    @Override
    public List<AssignmentRunDispatchTaskQueryRow> listRecentAssignmentRuns(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return mapper.selectRecentAssignmentRuns(safe);
    }

    @Override
    public int countTerminalAssignmentRunsSince(String terminalStatus, LocalDateTime since) {
        if (terminalStatus == null || since == null) {
            return 0;
        }
        Integer count = mapper.countTerminalAssignmentRunsSince(terminalStatus, since);
        return count == null ? 0 : count;
    }

    @Override
    public int countStartedAssignmentRunsSince(LocalDateTime since) {
        if (since == null) {
            return 0;
        }
        Integer count = mapper.countStartedAssignmentRunsSince(since);
        return count == null ? 0 : count;
    }

    @Override
    public int countPendingAssignmentRunOutbox() {
        Integer count = mapper.countPendingAssignmentRunOutbox();
        return count == null ? 0 : count;
    }
}
