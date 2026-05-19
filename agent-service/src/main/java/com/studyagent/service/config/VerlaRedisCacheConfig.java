package com.studyagent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.application.verla.VerlaContextQueryService;
import com.studyagent.service.application.verla.VerlaRedisCacheInvalidationSubscriber;
import com.studyagent.service.application.verla.cache.VerlaCacheJsonCodec;
import com.studyagent.service.application.verla.cache.VerlaCacheKeyFactory;
import com.studyagent.service.application.verla.cache.VerlaRedisContextCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class VerlaRedisCacheConfig {

    @Bean
    @ConditionalOnMissingBean
    VerlaCacheKeyFactory verlaCacheKeyFactory(VerlaContextCacheProperties properties) {
        return new VerlaCacheKeyFactory(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    VerlaCacheJsonCodec verlaCacheJsonCodec(ObjectMapper objectMapper) {
        return new VerlaCacheJsonCodec(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "verla.context-cache", name = "redis-enabled", havingValue = "true")
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean
    StringRedisTemplate verlaStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "verla.context-cache", name = "redis-enabled", havingValue = "true")
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    VerlaRedisContextCache verlaRedisContextCache(StringRedisTemplate redisTemplate,
                                                  VerlaCacheJsonCodec codec,
                                                  VerlaContextCacheProperties properties) {
        return new VerlaRedisContextCache(redisTemplate, codec, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "verla.context-cache", name = "redis-enabled", havingValue = "true")
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    VerlaRedisCacheInvalidationSubscriber verlaRedisCacheInvalidationSubscriber(ObjectMapper objectMapper,
                                                                                VerlaContextQueryService queryService) {
        return new VerlaRedisCacheInvalidationSubscriber(objectMapper, queryService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "verla.context-cache", name = "redis-enabled", havingValue = "true")
    @ConditionalOnBean({RedisConnectionFactory.class, VerlaRedisCacheInvalidationSubscriber.class})
    @ConditionalOnMissingBean(name = "verlaRedisCacheInvalidationListenerContainer")
    RedisMessageListenerContainer verlaRedisCacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            VerlaRedisCacheInvalidationSubscriber subscriber,
            VerlaCacheKeyFactory keyFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, ChannelTopic.of(keyFactory.invalidationChannel()));
        return container;
    }
}
