package com.studyagent.infra.mq;

import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "rabbitmq.integration", matches = "true")
class VerlaRabbitTopologyIntegrationTest {

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin rabbitAdmin;
    private RabbitTemplate rabbitTemplate;
    private final List<String> exchangesToDelete = new ArrayList<>();
    private final List<String> queuesToDelete = new ArrayList<>();

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

        rabbitAdmin = new RabbitAdmin(connectionFactory);
        rabbitTemplate = new RabbitTemplate(connectionFactory);
    }

    @AfterEach
    void tearDown() {
        for (String queue : queuesToDelete) {
            rabbitAdmin.deleteQueue(queue);
        }
        for (String exchange : exchangesToDelete) {
            rabbitAdmin.deleteExchange(exchange);
        }
        connectionFactory.destroy();
    }

    @Test
    void fanoutAlternateExchangeShouldCatchUnroutableEventRoutingKey() {
        String suffix = UUID.randomUUID().toString();
        String eventsExchange = "studyagent.test.events." + suffix;
        String unroutableExchange = "studyagent.test.unroutable." + suffix;
        String unroutableQueue = "verla.test.unroutable." + suffix;
        declareExchange(new FanoutExchange(unroutableExchange, false, true));
        declareQueue(new Queue(unroutableQueue, false, false, true));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(unroutableQueue))
                .to(new FanoutExchange(unroutableExchange)));

        declareExchange(new TopicExchange(
                eventsExchange,
                false,
                true,
                Map.of("alternate-exchange", unroutableExchange)));

        rabbitTemplate.send(eventsExchange, "verla.event.s99.assignment", message("unroutable"));

        Message received = rabbitTemplate.receive(unroutableQueue, 3000);
        assertThat(received).isNotNull();
        assertThat(body(received)).isEqualTo("unroutable");
    }

    @Test
    void topicDlxBindingHashShouldCatchDeadLettersWithOriginalRoutingKey() {
        String suffix = UUID.randomUUID().toString();
        String mainExchange = "studyagent.test.main." + suffix;
        String dlxExchange = "studyagent.test.dlx." + suffix;
        String workQueue = "verla.test.work." + suffix;
        String dlqQueue = "verla.test.dlq." + suffix;
        declareExchange(new DirectExchange(mainExchange, false, true));
        declareExchange(new TopicExchange(dlxExchange, false, true));
        declareQueue(QueueBuilder.nonDurable(workQueue)
                .autoDelete()
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .build());
        declareQueue(new Queue(dlqQueue, false, false, true));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(workQueue))
                .to(new DirectExchange(mainExchange))
                .with("cmd.assignment.run"));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(dlqQueue))
                .to(new TopicExchange(dlxExchange))
                .with("#"));

        rabbitTemplate.send(mainExchange, "cmd.assignment.run", message("dead-letter-me"));
        String deliveredBody = rabbitTemplate.execute(channel -> {
            GetResponse response = channel.basicGet(workQueue, false);
            assertThat(response).isNotNull();
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            return new String(response.getBody(), StandardCharsets.UTF_8);
        });
        assertThat(deliveredBody).isEqualTo("dead-letter-me");

        Message deadLetter = rabbitTemplate.receive(dlqQueue, 3000);
        assertThat(deadLetter).isNotNull();
        assertThat(body(deadLetter)).isEqualTo("dead-letter-me");
        assertThat(deadLetter.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("cmd.assignment.run");
    }

    @Test
    void normalEventRoutingShouldStillReachShardQueue() {
        String suffix = UUID.randomUUID().toString();
        String eventsExchange = "studyagent.test.events.normal." + suffix;
        String shardQueue = "verla.test.event.s00." + suffix;
        declareExchange(new TopicExchange(eventsExchange, false, true));
        declareQueue(new Queue(shardQueue, false, false, true));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(shardQueue))
                .to(new TopicExchange(eventsExchange))
                .with("verla.event.s00.#"));

        rabbitTemplate.send(eventsExchange, "verla.event.s00.assignment.started", message("normal"));

        Message received = rabbitTemplate.receive(shardQueue, 3000);
        assertThat(received).isNotNull();
        assertThat(body(received)).isEqualTo("normal");
    }

    @Test
    void normalCommandRoutingShouldStillReachCommandQueue() {
        String suffix = UUID.randomUUID().toString();
        String commandExchange = "studyagent.test.command." + suffix;
        String commandQueue = "verla.test.cmd.agent." + suffix;
        declareExchange(new DirectExchange(commandExchange, false, true));
        declareQueue(new Queue(commandQueue, false, false, true));
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(commandQueue))
                .to(new DirectExchange(commandExchange))
                .with("cmd.assignment.run"));

        rabbitTemplate.send(commandExchange, "cmd.assignment.run", message("command"));

        Message received = rabbitTemplate.receive(commandQueue, 3000);
        assertThat(received).isNotNull();
        assertThat(body(received)).isEqualTo("command");
    }

    private void declareExchange(org.springframework.amqp.core.Exchange exchange) {
        rabbitAdmin.declareExchange(exchange);
        exchangesToDelete.add(exchange.getName());
    }

    private void declareQueue(Queue queue) {
        rabbitAdmin.declareQueue(queue);
        queuesToDelete.add(queue.getName());
    }

    private static Message message(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .build();
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
