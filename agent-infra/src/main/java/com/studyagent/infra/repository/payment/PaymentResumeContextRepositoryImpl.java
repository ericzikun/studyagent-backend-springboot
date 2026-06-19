package com.studyagent.infra.repository.payment;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.PaymentResumeContextEntity;
import com.studyagent.infra.mapper.PaymentResumeContextMapper;
import com.studyagent.service.domain.payment.PaymentResumeContext;
import com.studyagent.service.domain.payment.PaymentResumeContextRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class PaymentResumeContextRepositoryImpl
        extends ServiceImpl<PaymentResumeContextMapper, PaymentResumeContextEntity>
        implements PaymentResumeContextRepository {

    @Override
    public PaymentResumeContext save(PaymentResumeContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        PaymentResumeContextEntity entity = toEntity(context);
        this.saveOrUpdate(entity);
        return toDomain(entity);
    }

    @Override
    public PaymentResumeContext findByTokenForUpdate(String resumeToken) {
        if (resumeToken == null || resumeToken.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByResumeTokenForUpdate(resumeToken));
    }

    @Override
    public void markResumed(Long id, LocalDateTime resumedAt) {
        if (id == null || resumedAt == null) {
            throw new IllegalArgumentException("id and resumedAt are required");
        }
        this.baseMapper.markResumed(id, resumedAt);
    }

    private PaymentResumeContext toDomain(PaymentResumeContextEntity entity) {
        if (entity == null) {
            return null;
        }
        return PaymentResumeContext.builder()
                .id(entity.getId())
                .resumeToken(entity.getResumeToken())
                .clerkUserId(entity.getClerkUserId())
                .scene(entity.getScene())
                .resourceId(entity.getResourceId())
                .idempotencyKey(entity.getIdempotencyKey())
                .payloadJson(entity.getPayloadJson())
                .status(entity.getStatus())
                .expiresAt(entity.getExpiresAt())
                .resumedAt(entity.getResumedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private PaymentResumeContextEntity toEntity(PaymentResumeContext context) {
        if (context == null) {
            return null;
        }
        PaymentResumeContextEntity entity = new PaymentResumeContextEntity();
        entity.setId(context.getId());
        entity.setResumeToken(context.getResumeToken());
        entity.setClerkUserId(context.getClerkUserId());
        entity.setScene(context.getScene());
        entity.setResourceId(context.getResourceId());
        entity.setIdempotencyKey(context.getIdempotencyKey());
        entity.setPayloadJson(context.getPayloadJson());
        entity.setStatus(context.getStatus());
        entity.setExpiresAt(context.getExpiresAt());
        entity.setResumedAt(context.getResumedAt());
        entity.setCreatedAt(context.getCreatedAt());
        entity.setUpdatedAt(context.getUpdatedAt());
        return entity;
    }
}
