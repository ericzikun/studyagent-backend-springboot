package com.studyagent.infra.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatchSchedulerTest {

    private FakeMqOutboxRepository mqOutboxRepository;
    private FakeRabbitTemplate rabbitTemplate;
    private OutboxDispatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        mqOutboxRepository = new FakeMqOutboxRepository();
        rabbitTemplate = new FakeRabbitTemplate();
        scheduler = new OutboxDispatchScheduler(
                mqOutboxRepository,
                rabbitTemplate,
                new ObjectMapper());
    }

    @Test
    void shouldRetryWhenMessageIsReturnedEvenIfBrokerConfirmAck() {
        MqOutbox message = verlaCommand("missing.routing.key", 0, 3);
        rabbitTemplate.returned = true;
        rabbitTemplate.confirmAck = true;

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isNull();
        assertThat(mqOutboxRepository.retryId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.retryError)
                .contains("NO_ROUTE")
                .contains("replyCode=312")
                .contains("exchange=studyagent.command")
                .contains("routingKey=missing.routing.key");
    }

    @Test
    void dispatchPendingMessagesShouldClaimBeforeSending() {
        MqOutbox claimed = claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-claimed");
        mqOutboxRepository.claimedMessages = List.of(claimed);
        rabbitTemplate.confirmAck = true;

        scheduler.dispatchPendingMessages();

        assertThat(mqOutboxRepository.claimCalled).isTrue();
        assertThat(mqOutboxRepository.findPendingCalled).isFalse();
        assertThat(mqOutboxRepository.sentId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.sentWorkerId).isEqualTo("worker-claimed");
    }

    @Test
    void shouldMarkAsSentWhenBrokerConfirmAckAndNoReturnedMessage() {
        MqOutbox message = claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-claimed");
        rabbitTemplate.confirmAck = true;

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.sentWorkerId).isEqualTo("worker-claimed");
        assertThat(mqOutboxRepository.retryId).isNull();
        assertThat(mqOutboxRepository.failedId).isNull();
    }

    @Test
    void shouldRetryWhenBrokerConfirmNack() {
        MqOutbox message = claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-claimed");
        rabbitTemplate.confirmAck = false;
        rabbitTemplate.confirmReason = "exchange unavailable";

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isNull();
        assertThat(mqOutboxRepository.retryId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.retryWorkerId).isEqualTo("worker-claimed");
        assertThat(mqOutboxRepository.retryError).contains("Broker NACK: exchange unavailable");
    }

    @Test
    void shouldMarkAsFailedWhenReturnedMessageExhaustsRetries() {
        MqOutbox message = verlaCommand("missing.routing.key", 2, 3);
        rabbitTemplate.returned = true;
        rabbitTemplate.confirmAck = true;

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isNull();
        assertThat(mqOutboxRepository.retryId).isNull();
        assertThat(mqOutboxRepository.failedId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.failedError)
                .contains("NO_ROUTE")
                .contains("routingKey=missing.routing.key");
    }

    @Test
    void immediateDispatchShouldClaimSingleMessageBeforeSending() {
        MqOutbox claimed = claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-immediate");
        mqOutboxRepository.claimByIdMessage = claimed;
        rabbitTemplate.confirmAck = true;

        scheduler.dispatchMessageById(1001L);

        assertThat(mqOutboxRepository.claimById).isEqualTo(1001L);
        assertThat(mqOutboxRepository.sentId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.sentWorkerId).isEqualTo("worker-immediate");
    }

    @Test
    void immediateDispatchShouldSkipWhenMessageAlreadyClaimedByAnotherWorker() {
        mqOutboxRepository.claimByIdMessage = null;

        scheduler.dispatchMessageById(1001L);

        assertThat(rabbitTemplate.sendCount).isZero();
        assertThat(mqOutboxRepository.sentId).isNull();
        assertThat(mqOutboxRepository.retryId).isNull();
        assertThat(mqOutboxRepository.failedId).isNull();
    }

    @Test
    void twoSchedulersShouldNotSendSameOutboxRowWhenSharingClaimRepository() {
        ClaimingMqOutboxRepository repository = new ClaimingMqOutboxRepository();
        repository.put(verlaCommand("cmd.assignment.run", 0, 3));
        FakeRabbitTemplate firstTemplate = new FakeRabbitTemplate();
        FakeRabbitTemplate secondTemplate = new FakeRabbitTemplate();
        OutboxDispatchScheduler firstScheduler = new OutboxDispatchScheduler(
                repository,
                firstTemplate,
                new ObjectMapper());
        OutboxDispatchScheduler secondScheduler = new OutboxDispatchScheduler(
                repository,
                secondTemplate,
                new ObjectMapper());

        firstScheduler.dispatchPendingMessages();
        secondScheduler.dispatchPendingMessages();

        assertThat(firstTemplate.sendCount + secondTemplate.sendCount).isEqualTo(1);
        assertThat(repository.findById(1001L).getStatus()).isEqualTo(MqOutbox.STATUS_SENT);
    }

    @Test
    void claimRepositoryShouldSkipActiveSendingAndReclaimExpiredSending() {
        ClaimingMqOutboxRepository repository = new ClaimingMqOutboxRepository();
        LocalDateTime now = LocalDateTime.now();
        repository.put(claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-old"));

        List<MqOutbox> activeClaim = repository.claimPendingMessages(
                1,
                "worker-new",
                now,
                now.plusSeconds(60));
        assertThat(activeClaim).isEmpty();

        repository.put(copy(repository.findById(1001L))
                .status(MqOutbox.STATUS_SENDING)
                .workerId("worker-old")
                .leaseUntil(now.minusSeconds(1))
                .build());
        List<MqOutbox> expiredClaim = repository.claimPendingMessages(
                1,
                "worker-new",
                now,
                now.plusSeconds(60));

        assertThat(expiredClaim).hasSize(1);
        assertThat(expiredClaim.get(0).getWorkerId()).isEqualTo("worker-new");
        assertThat(repository.findById(1001L).getStatus()).isEqualTo(MqOutbox.STATUS_SENDING);
    }

    @Test
    void onlyClaimWorkerShouldMarkOutboxAsSent() {
        ClaimingMqOutboxRepository repository = new ClaimingMqOutboxRepository();
        repository.put(claimedVerlaCommand("cmd.assignment.run", 0, 3, "worker-a"));

        repository.markAsSent(1001L, "worker-b");
        assertThat(repository.findById(1001L).getStatus()).isEqualTo(MqOutbox.STATUS_SENDING);

        repository.markAsSent(1001L, "worker-a");
        assertThat(repository.findById(1001L).getStatus()).isEqualTo(MqOutbox.STATUS_SENT);
    }

    private static MqOutbox verlaCommand(String routingKey, int retryCount, int maxRetries) {
        return MqOutbox.builder()
                .id(1001L)
                .eventId("cmd-1001")
                .action("cmd.assignment.run")
                .payload("{}")
                .status(MqOutbox.STATUS_UNSENT)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .correlationId("conv:10:turn:20:sess:30")
                .orderingKey("session:30")
                .schemaVersion(1)
                .conversationId(10L)
                .turnId(20L)
                .sessionId(30L)
                .exchange("studyagent.command")
                .routingKey(routingKey)
                .build();
    }

    private static MqOutbox claimedVerlaCommand(
            String routingKey,
            int retryCount,
            int maxRetries,
            String workerId) {
        return MqOutbox.builder()
                .id(1001L)
                .eventId("cmd-1001")
                .action("cmd.assignment.run")
                .payload("{}")
                .status(MqOutbox.STATUS_SENDING)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .workerId(workerId)
                .leaseUntil(LocalDateTime.now().plusSeconds(60))
                .lastClaimedAt(LocalDateTime.now())
                .correlationId("conv:10:turn:20:sess:30")
                .orderingKey("session:30")
                .schemaVersion(1)
                .conversationId(10L)
                .turnId(20L)
                .sessionId(30L)
                .exchange("studyagent.command")
                .routingKey(routingKey)
                .build();
    }

    private static MqOutbox.MqOutboxBuilder copy(MqOutbox message) {
        return MqOutbox.builder()
                .id(message.getId())
                .eventId(message.getEventId())
                .action(message.getAction())
                .taskId(message.getTaskId())
                .payload(message.getPayload())
                .status(message.getStatus())
                .retryCount(message.getRetryCount())
                .maxRetries(message.getMaxRetries())
                .nextRetryAt(message.getNextRetryAt())
                .errorMessage(message.getErrorMessage())
                .workerId(message.getWorkerId())
                .leaseUntil(message.getLeaseUntil())
                .lastClaimedAt(message.getLastClaimedAt())
                .correlationId(message.getCorrelationId())
                .orderingKey(message.getOrderingKey())
                .schemaVersion(message.getSchemaVersion())
                .conversationId(message.getConversationId())
                .turnId(message.getTurnId())
                .sessionId(message.getSessionId())
                .exchange(message.getExchange())
                .routingKey(message.getRoutingKey())
                .createdAt(message.getCreatedAt())
                .updatedAt(message.getUpdatedAt());
    }

    private static class FakeRabbitTemplate extends RabbitTemplate {
        boolean returned;
        boolean confirmAck = true;
        String confirmReason;
        int sendCount;

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
            sendCount++;
            if (returned) {
                correlationData.setReturned(new ReturnedMessage(
                        message,
                        312,
                        "NO_ROUTE",
                        exchange,
                        routingKey));
            }
            correlationData.getFuture().complete(new CorrelationData.Confirm(confirmAck, confirmReason));
        }
    }

    private static class FakeMqOutboxRepository implements MqOutboxRepository {
        Long sentId;
        Long retryId;
        String retryError;
        String sentWorkerId;
        String retryWorkerId;
        Long failedId;
        String failedError;
        boolean findPendingCalled;
        boolean claimCalled;
        Long claimById;
        List<MqOutbox> claimedMessages = Collections.emptyList();
        MqOutbox claimByIdMessage;

        @Override
        public MqOutbox save(MqOutbox mqOutbox) {
            return mqOutbox;
        }

        @Override
        public MqOutbox findById(Long id) {
            return null;
        }

        @Override
        public MqOutbox findByEventId(String eventId) {
            return null;
        }

        @Override
        public List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime) {
            findPendingCalled = true;
            return Collections.emptyList();
        }

        @Override
        public List<MqOutbox> claimPendingMessages(
                int limit,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            claimCalled = true;
            return claimedMessages;
        }

        @Override
        public MqOutbox claimMessage(
                Long id,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            claimById = id;
            return claimByIdMessage;
        }

        @Override
        public void markAsSent(Long id) {
            this.sentId = id;
        }

        @Override
        public void markAsSent(Long id, String workerId) {
            this.sentId = id;
            this.sentWorkerId = workerId;
        }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
            this.retryId = id;
            this.retryError = errorMessage;
        }

        @Override
        public void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt) {
            this.retryId = id;
            this.retryWorkerId = workerId;
            this.retryError = errorMessage;
        }

        @Override
        public void markAsFailed(Long id, String errorMessage) {
            this.failedId = id;
            this.failedError = errorMessage;
        }

        @Override
        public void markAsFailed(Long id, String workerId, String errorMessage) {
            this.failedId = id;
            this.failedError = errorMessage;
        }
    }

    private static class ClaimingMqOutboxRepository implements MqOutboxRepository {
        private final Map<Long, MqOutbox> rows = new HashMap<>();

        void put(MqOutbox message) {
            rows.put(message.getId(), message);
        }

        @Override
        public synchronized MqOutbox save(MqOutbox mqOutbox) {
            rows.put(mqOutbox.getId(), mqOutbox);
            return mqOutbox;
        }

        @Override
        public synchronized MqOutbox findById(Long id) {
            return rows.get(id);
        }

        @Override
        public synchronized MqOutbox findByEventId(String eventId) {
            return rows.values().stream()
                    .filter(row -> Objects.equals(row.getEventId(), eventId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public synchronized List<MqOutbox> findPendingMessages(int limit, LocalDateTime currentTime) {
            return rows.values().stream()
                    .filter(row -> row.getStatus() == MqOutbox.STATUS_UNSENT)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized List<MqOutbox> claimPendingMessages(
                int limit,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            return rows.values().stream()
                    .sorted(Comparator.comparing(MqOutbox::getId))
                    .filter(row -> isClaimable(row, currentTime))
                    .limit(limit)
                    .map(row -> claim(row, workerId, currentTime, leaseUntil))
                    .toList();
        }

        @Override
        public synchronized MqOutbox claimMessage(
                Long id,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            MqOutbox row = rows.get(id);
            if (!isClaimable(row, currentTime)) {
                return null;
            }
            return claim(row, workerId, currentTime, leaseUntil);
        }

        @Override
        public synchronized void markAsSent(Long id) {
            MqOutbox row = rows.get(id);
            if (row != null) {
                rows.put(id, copy(row).status(MqOutbox.STATUS_SENT).build());
            }
        }

        @Override
        public synchronized void markAsSent(Long id, String workerId) {
            MqOutbox row = rows.get(id);
            if (row != null
                    && row.getStatus() == MqOutbox.STATUS_SENDING
                    && Objects.equals(row.getWorkerId(), workerId)) {
                rows.put(id, copy(row)
                        .status(MqOutbox.STATUS_SENT)
                        .leaseUntil(null)
                        .nextRetryAt(null)
                        .errorMessage(null)
                        .build());
            }
        }

        @Override
        public synchronized void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
            MqOutbox row = rows.get(id);
            if (row != null) {
                rows.put(id, copy(row)
                        .retryCount(row.getRetryCount() + 1)
                        .errorMessage(errorMessage)
                        .nextRetryAt(nextRetryAt)
                        .build());
            }
        }

        @Override
        public synchronized void markForRetry(
                Long id,
                String workerId,
                String errorMessage,
                LocalDateTime nextRetryAt) {
            MqOutbox row = rows.get(id);
            if (row != null
                    && row.getStatus() == MqOutbox.STATUS_SENDING
                    && Objects.equals(row.getWorkerId(), workerId)) {
                rows.put(id, copy(row)
                        .status(MqOutbox.STATUS_UNSENT)
                        .retryCount(row.getRetryCount() + 1)
                        .workerId(null)
                        .leaseUntil(null)
                        .errorMessage(errorMessage)
                        .nextRetryAt(nextRetryAt)
                        .build());
            }
        }

        @Override
        public synchronized void markAsFailed(Long id, String errorMessage) {
            MqOutbox row = rows.get(id);
            if (row != null) {
                rows.put(id, copy(row)
                        .status(MqOutbox.STATUS_FAILED)
                        .retryCount(row.getRetryCount() + 1)
                        .errorMessage(errorMessage)
                        .build());
            }
        }

        @Override
        public synchronized void markAsFailed(Long id, String workerId, String errorMessage) {
            MqOutbox row = rows.get(id);
            if (row != null
                    && row.getStatus() == MqOutbox.STATUS_SENDING
                    && Objects.equals(row.getWorkerId(), workerId)) {
                rows.put(id, copy(row)
                        .status(MqOutbox.STATUS_FAILED)
                        .retryCount(row.getRetryCount() + 1)
                        .leaseUntil(null)
                        .errorMessage(errorMessage)
                        .build());
            }
        }

        private boolean isClaimable(MqOutbox row, LocalDateTime currentTime) {
            if (row == null || row.getRetryCount() >= row.getMaxRetries()) {
                return false;
            }
            boolean retryDue = row.getNextRetryAt() == null || !row.getNextRetryAt().isAfter(currentTime);
            boolean unsentDue = row.getStatus() == MqOutbox.STATUS_UNSENT && retryDue;
            boolean expiredSending = row.getStatus() == MqOutbox.STATUS_SENDING
                    && row.getLeaseUntil() != null
                    && !row.getLeaseUntil().isAfter(currentTime);
            return unsentDue || expiredSending;
        }

        private MqOutbox claim(
                MqOutbox row,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            MqOutbox claimed = copy(row)
                    .status(MqOutbox.STATUS_SENDING)
                    .workerId(workerId)
                    .leaseUntil(leaseUntil)
                    .lastClaimedAt(currentTime)
                    .errorMessage(null)
                    .build();
            rows.put(row.getId(), claimed);
            return claimed;
        }
    }
}
