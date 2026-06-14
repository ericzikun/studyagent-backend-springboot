package com.studyagent.start.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.studyagent.common.datetime.OffsetLocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

/**
 * Jackson 配置类
 * 
 * 解决大文件上传时 Base64 字符串超过默认限制的问题：
 * String length (xxx) exceeds the maximum length (20000000)
 * 
 * 将字符串最大长度从默认的 20MB 增加到 100MB
 * 
 * 同时优化流式写入，避免大响应导致 Broken pipe 错误
 */
@Configuration
public class JacksonConfig {

    /**
     * 配置 ObjectMapper，增加字符串长度限制并优化流式写入
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
        
        // 🆕 优化流式写入，避免大响应导致 Broken pipe
        // 不自动关闭输出流，交由容器管理（避免客户端断开时异常）
        objectMapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        
        // 🆕 启用自动刷新，提高写入效率
        objectMapper.configure(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM, true);

        // LocalDateTime 统一输出带 offset 的 ISO 字符串（如 2026-06-03T12:00:00+08:00）
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new SimpleModule()
                .addSerializer(LocalDateTime.class, new OffsetLocalDateTimeSerializer()));
        
        return objectMapper;
    }
}
