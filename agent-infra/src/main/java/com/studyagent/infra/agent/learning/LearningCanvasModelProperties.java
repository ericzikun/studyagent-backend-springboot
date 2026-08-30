package com.studyagent.infra.agent.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Learning Canvas Demo 模型属性
 * <p>
 * 读取 OpenAI 兼容配置（spring.ai.openai.*），与 java 分支 mini-agent 同一套约定。
 * 仅新增，不改旧配置语义。
 */
@Component
public class LearningCanvasModelProperties {

    private static final Logger log = LoggerFactory.getLogger(LearningCanvasModelProperties.class);

    private final String modelName;
    private final double temperature;
    private final Integer maxTokens;

    public LearningCanvasModelProperties(
            @Value("${spring.ai.openai.chat.options.model:${OPENAI_MODEL:deepseek-v4-flash}}") String modelName,
            @Value("${spring.ai.openai.chat.options.temperature:${OPENAI_TEMPERATURE:0.4}}") Double temperature,
            @Value("${learning.canvas.max-tokens:${OPENAI_MAX_TOKENS:16000}}") Integer maxTokens) {
        this.modelName = modelName == null || modelName.isBlank() ? "deepseek-v4-flash" : modelName.trim();
        this.temperature = temperature == null ? 0.4 : temperature;
        this.maxTokens = maxTokens == null ? 16000 : maxTokens;
    }

    public String modelName() {
        return modelName;
    }

    public double temperature() {
        return temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public void logResolved() {
        log.info("[LearningCanvas] model resolved -> modelName={}, temperature={}, maxTokens={}",
                modelName, temperature, maxTokens);
    }
}
