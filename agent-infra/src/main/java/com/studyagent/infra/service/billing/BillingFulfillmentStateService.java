package com.studyagent.infra.service.billing;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.BillingEntitlementFulfillmentEntity;
import com.studyagent.infra.mapper.BillingEntitlementFulfillmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingFulfillmentStateService {
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final BillingEntitlementFulfillmentMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordPaymentAccepted(PaymentAcceptedCommand command) {
        LocalDateTime now = LocalDateTime.now();
        BillingEntitlementFulfillmentEntity entity = new BillingEntitlementFulfillmentEntity();
        entity.setPaymentKey(command.paymentKey());
        entity.setSourceType(command.sourceType());
        entity.setSourceId(command.sourceId());
        entity.setSourceEventId(command.sourceEventId());
        entity.setRechargeOrderId(command.rechargeOrderId());
        entity.setPurchaseType(command.purchaseType());
        entity.setProductCode(command.productCode());
        entity.setPaymentStatus("accepted");
        entity.setFulfillmentStatus("pending");
        entity.setPaymentAcceptedAt(now);
        entity.setFulfillmentStartedAt(now);
        entity.setAttemptCount(1);
        try {
            boolean inserted = mapper.insert(entity) == 1;
            if (inserted) {
                publishPayment(command, "success");
            }
            return inserted;
        } catch (DuplicateKeyException duplicate) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                    .eq(BillingEntitlementFulfillmentEntity::getPaymentKey, command.paymentKey())
                    .and(status -> status
                            .and(row -> row
                                    .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "pending")
                                    .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "not_required"))
                            .or(row -> row
                                    .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "failed")
                                    .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "not_required")))
                    .set(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                    .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "pending")
                    .set(BillingEntitlementFulfillmentEntity::getPaymentAcceptedAt, now)
                    .set(BillingEntitlementFulfillmentEntity::getFulfillmentStartedAt, now)
                    .set(BillingEntitlementFulfillmentEntity::getSourceEventId, command.sourceEventId())
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorCode, null)
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorMessage, null)
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorAt, null)
                    .setSql("attempt_count = attempt_count + 1"));
            boolean changed = transitionResult(command.paymentKey(), "accepted/pending", updated);
            if (changed) {
                publishPayment(command, "success");
            }
            return changed;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordPaymentFailed(PaymentFailedCommand command) {
        LocalDateTime now = LocalDateTime.now();
        BillingEntitlementFulfillmentEntity entity = new BillingEntitlementFulfillmentEntity();
        entity.setPaymentKey(command.paymentKey());
        entity.setSourceType(command.sourceType());
        entity.setSourceId(command.sourceId());
        entity.setSourceEventId(command.sourceEventId());
        entity.setRechargeOrderId(command.rechargeOrderId());
        entity.setPurchaseType(command.purchaseType());
        entity.setProductCode(command.productCode());
        entity.setPaymentStatus("failed");
        entity.setFulfillmentStatus("not_required");
        entity.setLastErrorCode(normalizeErrorCode(command.errorCode()));
        entity.setLastErrorMessage(truncate(command.errorMessage()));
        entity.setLastErrorAt(now);
        entity.setAttemptCount(1);
        try {
            boolean inserted = mapper.insert(entity) == 1;
            if (inserted) {
                publishPayment(command.purchaseType(), command.productCode(), "error");
            }
            return inserted;
        } catch (DuplicateKeyException duplicate) {
            int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                    .eq(BillingEntitlementFulfillmentEntity::getPaymentKey, command.paymentKey())
                    .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "pending")
                    .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "not_required")
                    .set(BillingEntitlementFulfillmentEntity::getPaymentStatus, "failed")
                    .set(BillingEntitlementFulfillmentEntity::getSourceEventId, command.sourceEventId())
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorCode,
                            normalizeErrorCode(command.errorCode()))
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorMessage,
                            truncate(command.errorMessage()))
                    .set(BillingEntitlementFulfillmentEntity::getLastErrorAt, now)
                    .setSql("attempt_count = attempt_count + 1"));
            boolean changed = transitionResult(command.paymentKey(), "failed/not_required", updated);
            if (changed) {
                publishPayment(command.purchaseType(), command.productCode(), "error");
            }
            return changed;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markSucceeded(String paymentKey, String purchaseType, String productCode) {
        LocalDateTime now = LocalDateTime.now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                .eq(BillingEntitlementFulfillmentEntity::getPaymentKey, paymentKey)
                .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                .in(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "pending", "failed")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "succeeded")
                .set(BillingEntitlementFulfillmentEntity::getFulfilledAt, now)
                .set(BillingEntitlementFulfillmentEntity::getLastErrorCode, null)
                .set(BillingEntitlementFulfillmentEntity::getLastErrorMessage, null)
                .set(BillingEntitlementFulfillmentEntity::getLastErrorAt, null));
        boolean changed = transitionResult(paymentKey, "accepted/succeeded", updated);
        if (changed) {
            eventPublisher.publishEvent(new BillingEntitlementFulfilledEvent(
                    purchaseType, productCode, "success"));
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailedBySourceEvent(String sourceEventId, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        BillingEntitlementFulfillmentEntity current = mapper.selectOne(
                new LambdaQueryWrapper<BillingEntitlementFulfillmentEntity>()
                        .eq(BillingEntitlementFulfillmentEntity::getSourceEventId, sourceEventId)
                        .last("LIMIT 1"));
        int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                .eq(BillingEntitlementFulfillmentEntity::getSourceEventId, sourceEventId)
                .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "pending")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "failed")
                .set(BillingEntitlementFulfillmentEntity::getLastErrorCode, normalizeErrorCode(errorCode))
                .set(BillingEntitlementFulfillmentEntity::getLastErrorMessage, truncate(errorMessage))
                .set(BillingEntitlementFulfillmentEntity::getLastErrorAt, now)
                .setSql("attempt_count = attempt_count + 1"));
        boolean changed = transitionResult(sourceEventId, "accepted/failed", updated);
        if (changed && current != null) {
            eventPublisher.publishEvent(new BillingEntitlementFulfilledEvent(
                    current.getPurchaseType(), current.getProductCode(), "error"));
        }
        return changed;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRetrying(String paymentKey) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                .eq(BillingEntitlementFulfillmentEntity::getPaymentKey, paymentKey)
                .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "failed")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "pending")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStartedAt, LocalDateTime.now()));
        return transitionResult(paymentKey, "accepted/pending retry", updated);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRefunded(String paymentKey) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                .eq(BillingEntitlementFulfillmentEntity::getPaymentKey, paymentKey)
                .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                .set(BillingEntitlementFulfillmentEntity::getPaymentStatus, "refunded")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "not_required")
                .set(BillingEntitlementFulfillmentEntity::getLastErrorCode, null)
                .set(BillingEntitlementFulfillmentEntity::getLastErrorMessage, null)
                .set(BillingEntitlementFulfillmentEntity::getLastErrorAt, null));
        return transitionResult(paymentKey, "refunded/not_required", updated);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetryingBySourceEvent(String sourceEventId) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<BillingEntitlementFulfillmentEntity>()
                .eq(BillingEntitlementFulfillmentEntity::getSourceEventId, sourceEventId)
                .eq(BillingEntitlementFulfillmentEntity::getPaymentStatus, "accepted")
                .eq(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "failed")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStatus, "pending")
                .set(BillingEntitlementFulfillmentEntity::getFulfillmentStartedAt, LocalDateTime.now()));
        return updated == 1;
    }

    private boolean transitionResult(String reference, String target, int updated) {
        if (updated == 0) {
            log.warn("Billing fulfillment transition skipped: reference={}, target={}", reference, target);
            return false;
        }
        return true;
    }

    private String normalizeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "unknown";
        }
        return errorCode.length() > 64 ? errorCode.substring(0, 64) : errorCode;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : message;
    }

    private void publishPayment(PaymentAcceptedCommand command, String result) {
        publishPayment(command.purchaseType(), command.productCode(), result);
    }

    private void publishPayment(String purchaseType, String productCode, String result) {
        eventPublisher.publishEvent(new BillingPaymentAcceptedEvent(purchaseType, productCode, result));
    }

    public record PaymentAcceptedCommand(
            String paymentKey,
            String sourceType,
            String sourceId,
            String sourceEventId,
            Long rechargeOrderId,
            String purchaseType,
            String productCode) {
    }

    public record PaymentFailedCommand(
            String paymentKey,
            String sourceType,
            String sourceId,
            String sourceEventId,
            Long rechargeOrderId,
            String purchaseType,
            String productCode,
            String errorCode,
            String errorMessage) {
    }
}
