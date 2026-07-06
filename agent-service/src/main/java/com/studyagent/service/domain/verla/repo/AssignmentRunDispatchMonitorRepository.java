package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.application.verla.admin.dto.AssignmentRunDispatchTaskQueryRow;

import java.time.LocalDateTime;
import java.util.List;

public interface AssignmentRunDispatchMonitorRepository {

    List<AssignmentRunDispatchTaskQueryRow> listRecentAssignmentRuns(int limit);

    int countTerminalAssignmentRunsSince(String terminalStatus, LocalDateTime since);

    int countStartedAssignmentRunsSince(LocalDateTime since);

    int countPendingAssignmentRunOutbox();

    int countQueuedAssignmentRunSessions();
}
