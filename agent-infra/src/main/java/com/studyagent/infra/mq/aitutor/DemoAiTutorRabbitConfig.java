package com.studyagent.infra.mq.aitutor;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI Tutor demo 事件队列：绑定 studyagent.events 的 verla.event.aitutor.#。
 * 只新增队列与绑定，不重复声明 exchange（避免 PRECONDITION_FAILED）。
 */
@Configuration
public class DemoAiTutorRabbitConfig {

    public static final String AITUTOR_EVENT_QUEUE = "verla.event.aitutor";
    public static final String AITUTOR_EVENT_ROUTING_PREFIX = "verla.event.aitutor.#";

    @Bean
    public Queue demoAiTutorEventQueue() {
        return new Queue(AITUTOR_EVENT_QUEUE, true, false, false);
    }

    @Bean
    public Binding demoAiTutorEventBinding(Queue demoAiTutorEventQueue,
                                            TopicExchange verlaEventsExchange) {
        return BindingBuilder.bind(demoAiTutorEventQueue)
                .to(verlaEventsExchange)
                .with(AITUTOR_EVENT_ROUTING_PREFIX);
    }
}
