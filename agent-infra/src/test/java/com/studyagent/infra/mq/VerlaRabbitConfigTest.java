package com.studyagent.infra.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.ExchangeTypes;

import static org.assertj.core.api.Assertions.assertThat;

class VerlaRabbitConfigTest {

    private final VerlaRabbitConfig config = new VerlaRabbitConfig();

    @Test
    void dlxShouldBeTopicExchangeAndDlqShouldBindAllRoutingKeys() {
        assertThat(config.verlaDlxExchange().getType()).isEqualTo(ExchangeTypes.TOPIC);

        Binding binding = config.verlaDlqBindingAll(
                config.verlaDlqQueue(),
                config.verlaDlxExchange());

        assertThat(binding.getExchange()).isEqualTo(VerlaRabbitConfig.DLX_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo("#");
    }

    @Test
    void unroutableExchangeShouldBeFanoutAndQueueShouldBindWithoutRoutingKeyMatch() {
        assertThat(config.verlaUnroutableExchange().getType()).isEqualTo(ExchangeTypes.FANOUT);

        Binding binding = config.verlaUnroutableBinding(
                config.verlaUnroutableQueue(),
                config.verlaUnroutableExchange());

        assertThat(binding.getExchange()).isEqualTo(VerlaRabbitConfig.UNROUTABLE_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEmpty();
    }
}
