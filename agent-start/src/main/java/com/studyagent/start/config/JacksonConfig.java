package com.studyagent.start.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson 配置类
 * 
 * 解决大文件上传时 Base64 字符串超过默认限制的问题：
 * String length (xxx) exceeds the maximum length (20000000)
 * 
 * 将字符串最大长度从默认的 20MB 增加到 100MB
 */
@Configuration
public class JacksonConfig {

    /**
     * 配置 ObjectMapper，增加字符串长度限制
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.build();
        
        // 配置 StreamReadConstraints，增加最大字符串长度
        // 默认是 20MB (20_000_000)，这里增加到 100MB
        StreamReadConstraints streamReadConstraints = StreamReadConstraints.builder()
                .maxStringLength(100_000_000)  // 100MB
                .maxNumberLength(10000)        // 数字最大长度
                .maxNestingDepth(1000)         // 最大嵌套深度
                .build();
        
        objectMapper.getFactory().setStreamReadConstraints(streamReadConstraints);
        
        return objectMapper;
    }
}
