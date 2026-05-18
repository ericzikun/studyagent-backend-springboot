package com.studyagent.infra.mq;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import java.util.List;

/**
 * 把动态生成的 Queue / Binding 注册到 BeanFactory，
 * 这样 RabbitAdmin 启动扫描时会自动 declare 它们。
 * <p>
 * Verla 因 shardCount 是配置项，不能用静态 @Bean 声明 N 个 queue，
 * 需要用这种方式动态注册。
 */
@Slf4j
@RequiredArgsConstructor
public class RabbitDeclarableRegistrar {

    private final ConfigurableBeanFactory beanFactory;
    private final List<VerlaRabbitConfig.ShardDeclarable> declarables;

    @PostConstruct
    public void register() {
        for (VerlaRabbitConfig.ShardDeclarable d : declarables) {
            String queueBeanName = "verlaEventQueue_" + d.name();
            String bindingBeanName = "verlaEventBinding_" + d.name();
            if (!beanFactory.containsBean(queueBeanName)) {
                beanFactory.registerSingleton(queueBeanName, d.queue());
            }
            if (!beanFactory.containsBean(bindingBeanName)) {
                beanFactory.registerSingleton(bindingBeanName, d.binding());
            }
            log.info("[Verla MQ] registered shard queue={}, binding pattern={}",
                    d.name(), d.binding().getRoutingKey());
        }
    }
}
