package com.studyagent.infra.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.mq.MqOutbox;
import com.studyagent.service.domain.mq.MqOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "rabbitmq.integration", matches = "true")
class OutboxDispatchSchedulerRabbitIntegrationTest {

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin rabbitAdmin;
    private String exchangeName;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("rabbitmq.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("rabbitmq.port", "5672"));
        String username = System.getProperty("rabbitmq.username", "studyagent");
        String password = System.getProperty("rabbitmq.password", "studyagent2024");
        String virtualHost = System.getProperty("rabbitmq.virtualHost", "/");

        connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(username);
        connectionFactory.setPassword(password);
        connectionFactory.setVirtualHost(virtualHost);
        connectionFactory.setPublisherConfirmType(ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        rabbitAdmin = new RabbitAdmin(connectionFactory);
        exchangeName = "studyagent.test.no-route." + UUID.randomUUID();
        rabbitAdmin.declareExchange(new DirectExchange(exchangeName, false, true));
    }

    @AfterEach
    void tearDown() {
        if (rabbitAdmin != null && exchangeName != null) {
            rabbitAdmin.deleteExchange(exchangeName);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldRetryReturnedMessageFromRealRabbitMqBroker() {
        FakeMqOutboxRepository repository = new FakeMqOutboxRepository();
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> {
        });
        OutboxDispatchScheduler scheduler = new OutboxDispatchScheduler(
                repository,
                rabbitTemplate,
                new ObjectMapper());

        scheduler.sendMessage(verlaCommand(exchangeName, "missing.routing.key"));

        assertThat(repository.sentId).isNull();
        assertThat(repository.retryId).isEqualTo(1001L);
        assertThat(repository.retryError)
                .contains("NO_ROUTE")
                .contains("replyCode=312")
                .contains("exchange=" + exchangeName)
                .contains("routingKey=missing.routing.key");
    }

    private static MqOutbox verlaCommand(String exchange, String routingKey) {
        return MqOutbox.builder()
                .id(1001L)
                .eventId("cmd-1001")
                .action("cmd.assignment.run")
                .payload("{}")
                .status(MqOutbox.STATUS_SENDING)
                .retryCount(0)
                .maxRetries(3)
                .workerId("worker-rabbit-test")
                .leaseUntil(LocalDateTime.now().plusSeconds(60))
                .correlationId("conv:10:turn:20:sess:30")
                .orderingKey("session:30")
                .schemaVersion(1)
                .conversationId(10L)
                .turnId(20L)
                .sessionId(30L)
                .exchange(exchange)
                .routingKey(routingKey)
                .build();
    }

    private static class FakeMqOutboxRepository implements MqOutboxRepository {
        Long sentId;
        Long retryId;
        String retryError;

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
        public List<MqOutbox> claimPendingMessages(
                int limit,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            return Collections.emptyList();
        }

        @Override
        public MqOutbox claimMessage(
                Long id,
                String workerId,
                LocalDateTime currentTime,
                LocalDateTime leaseUntil) {
            return null;
        }

        @Override
        public void markAsSent(Long id) {
            this.sentId = id;
        }

        @Override
        public void markAsSent(Long id, String workerId) {
            this.sentId = id;
        }

        @Override
        public void markForRetry(Long id, String errorMessage, LocalDateTime nextRetryAt) {
            this.retryId = id;
            this.retryError = errorMessage;
        }

        @Override
        public void markForRetry(Long id, String workerId, String errorMessage, LocalDateTime nextRetryAt) {
            this.retryId = id;
            this.retryError = errorMessage;
        }

        @Override
        public void markAsFailed(Long id, String errorMessage) {
        }

        @Override
        public void markAsFailed(Long id, String workerId, String errorMessage) {
        }
    }
}
