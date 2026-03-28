package com.studyagent.infra.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列和交换机配置
 */
@Configuration
public class RabbitMQConfig {

    public static final String COMMAND_EXCHANGE = "studyagent.command";

    public static final String TASK_EXECUTE_QUEUE = "studyagent.task.execute";
    public static final String TASK_EXECUTE_ROUTING_KEY = "EXECUTE_TASK";

    public static final String TASK_CONTROL_QUEUE = "studyagent.task.control";
    public static final String TASK_CONTROL_ROUTING_KEY = "STOP_TASK";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        // 开启 Mandatory 后，消息无法路由到队列时会触发 ReturnCallback
        template.setMandatory(true);
        return template;
    }

    @Bean
    public DirectExchange commandExchange() {
        return new DirectExchange(COMMAND_EXCHANGE, true, false);
    }

    @Bean
    public Queue taskExecuteQueue() {
        return new Queue(TASK_EXECUTE_QUEUE, true);
    }

    @Bean
    public Queue taskControlQueue() {
        return new Queue(TASK_CONTROL_QUEUE, true);
    }

    @Bean
    public Binding executeBinding(Queue taskExecuteQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(taskExecuteQueue).to(commandExchange).with(TASK_EXECUTE_ROUTING_KEY);
    }

    @Bean
    public Binding controlBinding(Queue taskControlQueue, DirectExchange commandExchange) {
        return BindingBuilder.bind(taskControlQueue).to(commandExchange).with(TASK_CONTROL_ROUTING_KEY);
    }
}
