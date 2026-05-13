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
import java.util.Collections;
import java.util.List;

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
    void shouldMarkAsSentWhenBrokerConfirmAckAndNoReturnedMessage() {
        MqOutbox message = verlaCommand("cmd.assignment.run", 0, 3);
        rabbitTemplate.confirmAck = true;

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isEqualTo(1001L);
        assertThat(mqOutboxRepository.retryId).isNull();
        assertThat(mqOutboxRepository.failedId).isNull();
    }

    @Test
    void shouldRetryWhenBrokerConfirmNack() {
        MqOutbox message = verlaCommand("cmd.assignment.run", 0, 3);
        rabbitTemplate.confirmAck = false;
        rabbitTemplate.confirmReason = "exchange unavailable";

        scheduler.sendMessage(message);

        assertThat(mqOutboxRepository.sentId).isNull();
        assertThat(mqOutboxRepository.retryId).isEqualTo(1001L);
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

    private static class FakeRabbitTemplate extends RabbitTemplate {
        boolean returned;
        boolean confirmAck = true;
        String confirmReason;

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
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
        Long failedId;
        String failedError;

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
            return Collections.emptyList();
        }

        @Override
        public void markAsSent(Long id) {
            this.sentId = id;
        }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
            this.retryId = id;
            this.retryError = errorMessage;
        }

        @Override
        public void markAsFailed(Long id, String errorMessage) {
            this.failedId = id;
            this.failedError = errorMessage;
        }
    }
}
