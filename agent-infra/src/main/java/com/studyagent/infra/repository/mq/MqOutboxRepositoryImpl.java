package com.studyagent.infra.repository.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.verla.dispatch.CapabilityRunDispatchActions;
import com.studyagent.common.verla.enums.VerlaCommandAction;
import com.studyagent.infra.entity.mq.MqOutboxEntity;
import com.studyagent.infra.mapper.mq.MqOutboxMapper;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MQ 事务发件箱仓储实现类
 */
@Repository
public class MqOutboxRepositoryImpl extends ServiceImpl<MqOutboxMapper, MqOutboxEntity> implements MqOutboxRepository {

    @Override
    public MqOutbox save(MqOutbox mqOutbox) {
        MqOutboxEntity entity = toEntity(mqOutbox);
        this.saveOrUpdate(entity);
        return toDomain(entity);
    }

    @Override
    public MqOutbox findById(Long id) {
        MqOutboxEntity entity = this.getById(id);
        return entity != null ? toDomain(entity) : null;
    }

    @Override
    public MqOutbox findByEventId(String eventId) {
        MqOutboxEntity entity = this.getOne(new LambdaQueryWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getEventId, eventId));
        return entity != null ? toDomain(entity) : null;
    }

    @Override
    public List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime) {
        List<MqOutboxEntity> entities = this.list(new LambdaQueryWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                .and(wrapper -> wrapper.isNull(MqOutboxEntity::getNextRetryAt)
                        .or().le(MqOutboxEntity::getNextRetryAt, currentTime))
                // 使用 where 保证 retry_count < max_retries 而不是仅仅在内存判断
                .apply("retry_count < max_retries")
                .orderByAsc(MqOutboxEntity::getCreatedAt)
                .last("LIMIT " + limit));

        return entities.stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<MqOutbox> claimPendingMessages(
            int limit,
            String workerId,
            LocalDateTime currentTime,
            LocalDateTime leaseUntil) {
        List<Long> candidateIds = this.list(claimableQuery(currentTime)
                        .orderByAsc(MqOutboxEntity::getCreatedAt)
                        .last("LIMIT " + limit))
                .stream()
                .map(MqOutboxEntity::getId)
                .collect(Collectors.toList());

        return candidateIds.stream()
                .map(id -> claimMessage(id, workerId, currentTime, leaseUntil))
                .filter(message -> message != null)
                .collect(Collectors.toList());
    }

    @Override
    public MqOutbox claimMessage(
            Long id,
            String workerId,
            LocalDateTime currentTime,
            LocalDateTime leaseUntil) {
        boolean claimed = this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .and(wrapper -> wrapper
                        .and(w -> w.eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                                .and(retry -> retry.isNull(MqOutboxEntity::getNextRetryAt)
                                        .or().le(MqOutboxEntity::getNextRetryAt, currentTime)))
                        .or(w -> w.eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                                .isNotNull(MqOutboxEntity::getLeaseUntil)
                                .le(MqOutboxEntity::getLeaseUntil, currentTime)))
                .apply("retry_count < max_retries")
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                .set(MqOutboxEntity::getWorkerId, workerId)
                .set(MqOutboxEntity::getLeaseUntil, leaseUntil)
                .set(MqOutboxEntity::getLastClaimedAt, currentTime)
                .set(MqOutboxEntity::getErrorMessage, null));
        if (!claimed) {
            return null;
        }

        MqOutboxEntity entity = this.getById(id);
        return entity != null ? toDomain(entity) : null;
    }

    private LambdaQueryWrapper<MqOutboxEntity> claimableQuery(LocalDateTime currentTime) {
        return new LambdaQueryWrapper<MqOutboxEntity>()
                .and(wrapper -> wrapper
                        .and(w -> w.eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                                .and(retry -> retry.isNull(MqOutboxEntity::getNextRetryAt)
                                        .or().le(MqOutboxEntity::getNextRetryAt, currentTime)))
                        .or(w -> w.eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                                .isNotNull(MqOutboxEntity::getLeaseUntil)
                                .le(MqOutboxEntity::getLeaseUntil, currentTime)))
                .apply("retry_count < max_retries");
    }

    @Override
    public void markAsSent(Long id) {
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENT));
    }

    @Override
    public void markAsSent(Long id, String workerId) {
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                .eq(MqOutboxEntity::getWorkerId, workerId)
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENT)
                .set(MqOutboxEntity::getLeaseUntil, null)
                .set(MqOutboxEntity::getNextRetryAt, null)
                .set(MqOutboxEntity::getErrorMessage, null));
    }

    @Override
    public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
        // 使用数据库原生的重试次数递增，避免并发更新时的覆盖问题
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .setSql("retry_count = retry_count + 1")
                .set(MqOutboxEntity::getErrorMessage,
                        errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500)
                                : errorMessage)
                .set(MqOutboxEntity::getNextRetryAt, nextRetryAt));
    }

    @Override
    public void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt) {
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                .eq(MqOutboxEntity::getWorkerId, workerId)
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                .setSql("retry_count = retry_count + 1")
                .set(MqOutboxEntity::getWorkerId, null)
                .set(MqOutboxEntity::getLeaseUntil, null)
                .set(MqOutboxEntity::getErrorMessage, truncateError(errorMessage))
                .set(MqOutboxEntity::getNextRetryAt, nextRetryAt));
    }

    @Override
    public void markAsFailed(Long id, String errorMessage) {
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .setSql("retry_count = retry_count + 1")
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_FAILED)
                .set(MqOutboxEntity::getErrorMessage,
                        errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500)
                                : errorMessage));
    }

    @Override
    public void markAsFailed(Long id, String workerId, String errorMessage) {
        this.update(new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                .eq(MqOutboxEntity::getWorkerId, workerId)
                .setSql("retry_count = retry_count + 1")
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_FAILED)
                .set(MqOutboxEntity::getLeaseUntil, null)
                .set(MqOutboxEntity::getErrorMessage, truncateError(errorMessage)));
    }

    @Override
    public void releaseClaim(Long id, String workerId) {
        LambdaUpdateWrapper<MqOutboxEntity> wrapper = new LambdaUpdateWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getId, id)
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_SENDING)
                .set(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                .set(MqOutboxEntity::getWorkerId, null)
                .set(MqOutboxEntity::getLeaseUntil, null);
        if (workerId != null) {
            wrapper.eq(MqOutboxEntity::getWorkerId, workerId);
        }
        this.update(wrapper);
    }

    private static final List<String> GATED_ASSIGNMENT_RUN_ACTIONS = List.of(
            VerlaCommandAction.CMD_ASSIGNMENT_RUN.getCode(),
            VerlaCommandAction.CMD_AGENT_RETRY.getCode());

    @Override
    public int countDeferredAssignmentRunAhead(Long id, LocalDateTime createdAt) {
        if (id == null || createdAt == null) {
            return 0;
        }
        long count = this.count(new LambdaQueryWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                .in(MqOutboxEntity::getAction, GATED_ASSIGNMENT_RUN_ACTIONS)
                .and(wrapper -> wrapper
                        .lt(MqOutboxEntity::getCreatedAt, createdAt)
                        .or(nested -> nested
                                .eq(MqOutboxEntity::getCreatedAt, createdAt)
                                .lt(MqOutboxEntity::getId, id))));
        return Math.toIntExact(count);
    }

    @Override
    public int countDeferredCapabilityRunAhead(Long id, String action, LocalDateTime createdAt) {
        if (id == null || createdAt == null || !CapabilityRunDispatchActions.isGated(action)) {
            return 0;
        }
        long count = this.count(new LambdaQueryWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getStatus, MqOutbox.STATUS_UNSENT)
                .eq(MqOutboxEntity::getAction, action)
                .and(wrapper -> wrapper
                        .lt(MqOutboxEntity::getCreatedAt, createdAt)
                        .or(nested -> nested
                                .eq(MqOutboxEntity::getCreatedAt, createdAt)
                                .lt(MqOutboxEntity::getId, id))));
        return Math.toIntExact(count);
    }

    @Override
    public Integer findLatestStatusBySessionIdAndActions(Long sessionId, List<String> actions) {
        if (sessionId == null || actions == null || actions.isEmpty()) {
            return null;
        }
        MqOutboxEntity entity = this.getOne(new LambdaQueryWrapper<MqOutboxEntity>()
                .eq(MqOutboxEntity::getSessionId, sessionId)
                .in(MqOutboxEntity::getAction, actions)
                .orderByDesc(MqOutboxEntity::getId)
                .last("LIMIT 1"), false);
        return entity == null ? null : entity.getStatus();
    }

    private String truncateError(String errorMessage) {
        return errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500)
                : errorMessage;
    }

    // --- Converter Methods ---

    private MqOutbox toDomain(MqOutboxEntity entity) {
        if (entity == null) {
            return null;
        }
        return MqOutbox.builder()
                .id(entity.getId())
                .eventId(entity.getEventId())
                .action(entity.getAction())
                .taskId(entity.getTaskId())
                .payload(entity.getPayload())
                .status(entity.getStatus())
                .retryCount(entity.getRetryCount())
                .maxRetries(entity.getMaxRetries())
                .nextRetryAt(entity.getNextRetryAt())
                .errorMessage(entity.getErrorMessage())
                .workerId(entity.getWorkerId())
                .leaseUntil(entity.getLeaseUntil())
                .lastClaimedAt(entity.getLastClaimedAt())
                .correlationId(entity.getCorrelationId())
                .orderingKey(entity.getOrderingKey())
                .schemaVersion(entity.getSchemaVersion())
                .conversationId(entity.getConversationId())
                .turnId(entity.getTurnId())
                .sessionId(entity.getSessionId())
                .exchange(entity.getExchange())
                .routingKey(entity.getRoutingKey())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private MqOutboxEntity toEntity(MqOutbox domain) {
        if (domain == null) {
            return null;
        }
        return new MqOutboxEntity()
                .setId(domain.getId())
                .setEventId(domain.getEventId())
                .setAction(domain.getAction())
                .setTaskId(domain.getTaskId())
                .setPayload(domain.getPayload())
                .setStatus(domain.getStatus())
                .setRetryCount(domain.getRetryCount())
                .setMaxRetries(domain.getMaxRetries())
                .setNextRetryAt(domain.getNextRetryAt())
                .setErrorMessage(domain.getErrorMessage())
                .setWorkerId(domain.getWorkerId())
                .setLeaseUntil(domain.getLeaseUntil())
                .setLastClaimedAt(domain.getLastClaimedAt())
                .setCorrelationId(domain.getCorrelationId())
                .setOrderingKey(domain.getOrderingKey())
                .setSchemaVersion(domain.getSchemaVersion())
                .setConversationId(domain.getConversationId())
                .setTurnId(domain.getTurnId())
                .setSessionId(domain.getSessionId())
                .setExchange(domain.getExchange())
                .setRoutingKey(domain.getRoutingKey())
                .setCreatedAt(domain.getCreatedAt())
                .setUpdatedAt(domain.getUpdatedAt());
    }
}
