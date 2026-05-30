package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaWorkforceTaskEntity;
import com.studyagent.infra.mapper.verla.VerlaWorkforceTaskMapper;
import com.studyagent.service.domain.verla.WorkforceTaskProgressSnapshot;
import com.studyagent.service.domain.verla.VerlaWorkforceTask;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class VerlaWorkforceTaskRepositoryImpl
        extends ServiceImpl<VerlaWorkforceTaskMapper, VerlaWorkforceTaskEntity>
        implements VerlaWorkforceTaskRepository {

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed");

    @Override
    public Optional<VerlaWorkforceTask> findBySessionAndNode(Long sessionId, String nodeId) {
        return Optional.ofNullable(
                this.baseMapper.selectBySessionAndNode(sessionId, nodeId)).map(this::toDomain);
    }

    @Override
    public List<VerlaWorkforceTask> listBySession(Long sessionId) {
        return this.baseMapper.selectBySession(sessionId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaWorkforceTask> listByConversation(Long conversationId) {
        return this.baseMapper.selectByConversation(conversationId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public WorkforceTaskProgressSnapshot aggregateProgressBySession(Long sessionId) {
        if (sessionId == null) {
            return WorkforceTaskProgressSnapshot.empty();
        }
        List<VerlaWorkforceTaskEntity> rows = this.baseMapper.selectBySession(sessionId);
        if (rows == null || rows.isEmpty()) {
            return WorkforceTaskProgressSnapshot.empty();
        }

        int totalTaskCount = 0;
        int completedTaskCount = 0;
        int activeTaskCount = 0;
        Integer composeTotalRounds = null;
        Integer composeCurrentRound = null;

        for (VerlaWorkforceTaskEntity row : rows) {
            if (row == null) {
                continue;
            }
            String kind = row.getNodeKind() == null ? "" : row.getNodeKind().trim().toLowerCase();
            if ("plan".equals(kind)) {
                // plan 行：读 compose 总轮次（planTaskCount 为旧字段，composeTotalRounds 为新字段）
                Integer rounds = row.getComposeTotalRounds() != null
                        ? row.getComposeTotalRounds() : row.getPlanTaskCount();
                if (rounds != null && rounds > 0) {
                    composeTotalRounds = composeTotalRounds == null
                            ? rounds : Math.max(composeTotalRounds, rounds);
                }
            } else if ("compose".equals(kind)) {
                // compose 行：读当前轮次和总轮次（取最新/最大值）
                if (row.getComposeTotalRounds() != null && row.getComposeTotalRounds() > 0) {
                    composeTotalRounds = composeTotalRounds == null
                            ? row.getComposeTotalRounds()
                            : Math.max(composeTotalRounds, row.getComposeTotalRounds());
                }
                if (row.getComposeCurrentRound() != null && row.getComposeCurrentRound() > 0) {
                    composeCurrentRound = composeCurrentRound == null
                            ? row.getComposeCurrentRound()
                            : Math.max(composeCurrentRound, row.getComposeCurrentRound());
                }
            } else if ("task".equals(kind)) {
                totalTaskCount++;
                String status = row.getStatus() == null ? "" : row.getStatus().trim().toLowerCase();
                if ("completed".equals(status)) {
                    completedTaskCount++;
                } else if ("running".equals(status)) {
                    activeTaskCount++;
                }
            }
        }

        return new WorkforceTaskProgressSnapshot(
                totalTaskCount,
                completedTaskCount,
                activeTaskCount,
                composeTotalRounds,
                composeCurrentRound);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaWorkforceTask upsertBySessionNode(VerlaWorkforceTask patch) {
        if (patch.getSessionId() == null || patch.getNodeId() == null) {
            throw new IllegalArgumentException("sessionId and nodeId are required for upsertBySessionNode");
        }
        VerlaWorkforceTaskEntity existing =
                this.baseMapper.selectBySessionAndNode(patch.getSessionId(), patch.getNodeId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaWorkforceTaskEntity entity = new VerlaWorkforceTaskEntity()
                    .setConversationId(patch.getConversationId())
                    .setTurnId(patch.getTurnId())
                    .setSessionId(patch.getSessionId())
                    .setNodeId(patch.getNodeId())
                    .setCamelTaskId(patch.getCamelTaskId())
                    .setNodeKind(patch.getNodeKind() != null ? patch.getNodeKind() : "task")
                    .setTaskName(patch.getTaskName())
                    .setTaskType(patch.getTaskType())
                    .setDescription(patch.getDescription())
                    .setTaskAgent(patch.getTaskAgent())
                    .setStatus(patch.getStatus() != null ? patch.getStatus() : "queued")
                    .setContent(patch.getContent())
                    .setPlanStepsJson(patch.getPlanStepsJson())
                    .setPlanTaskCount(patch.getPlanTaskCount())
                    .setSortOrder(patch.getSortOrder() != null ? patch.getSortOrder() : 0)
                    .setStartedAt(patch.getStartedAt())
                    .setEndedAt(patch.getEndedAt())
                    .setProcessingTimeMs(patch.getProcessingTimeMs())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        // 终态不可回退到中间态
        if (isTerminal(existing.getStatus()) && !isTerminal(patch.getStatus())) {
            return toDomain(existing);
        }

        if (patch.getStatus() != null)          existing.setStatus(patch.getStatus());
        if (patch.getTaskName() != null)         existing.setTaskName(patch.getTaskName());
        if (patch.getTaskType() != null)         existing.setTaskType(patch.getTaskType());
        if (patch.getDescription() != null)      existing.setDescription(patch.getDescription());
        if (patch.getTaskAgent() != null)        existing.setTaskAgent(patch.getTaskAgent());
        if (patch.getContent() != null)          existing.setContent(patch.getContent());
        if (patch.getPlanStepsJson() != null)    existing.setPlanStepsJson(patch.getPlanStepsJson());
        if (patch.getPlanTaskCount() != null)    existing.setPlanTaskCount(patch.getPlanTaskCount());
        if (patch.getSortOrder() != null)        existing.setSortOrder(patch.getSortOrder());
        if (patch.getStartedAt() != null)        existing.setStartedAt(patch.getStartedAt());
        if (patch.getEndedAt() != null)          existing.setEndedAt(patch.getEndedAt());
        if (patch.getProcessingTimeMs() != null) existing.setProcessingTimeMs(patch.getProcessingTimeMs());
        existing.setUpdatedAt(now);

        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.toLowerCase());
    }

    private VerlaWorkforceTask toDomain(VerlaWorkforceTaskEntity e) {
        if (e == null) return null;
        return VerlaWorkforceTask.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .nodeId(e.getNodeId())
                .camelTaskId(e.getCamelTaskId())
                .nodeKind(e.getNodeKind())
                .taskName(e.getTaskName())
                .taskType(e.getTaskType())
                .description(e.getDescription())
                .taskAgent(e.getTaskAgent())
                .status(e.getStatus())
                .content(e.getContent())
                .planStepsJson(e.getPlanStepsJson())
                .planTaskCount(e.getPlanTaskCount())
                .sortOrder(e.getSortOrder())
                .startedAt(e.getStartedAt())
                .endedAt(e.getEndedAt())
                .processingTimeMs(e.getProcessingTimeMs())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
