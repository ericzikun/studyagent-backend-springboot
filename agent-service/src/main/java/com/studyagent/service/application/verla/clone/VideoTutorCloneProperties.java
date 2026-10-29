package com.studyagent.service.application.verla.clone;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ahaboard-clone 后端的 Java 复刻配置。
 *
 * 默认走 OpenRouter 的 deepseek-v4.1-flash（与 clone 完全一致），
 * 也支持 aiberm 兜底；密钥默认复用 models.yaml 里的别名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "verla.video-tutor.clone")
public class VideoTutorCloneProperties {

    private boolean enabled = true;

    private String modelsYaml = "";

    private String apiKey = "";

    private String apiKeyRef = "open_router";

    private String baseUrl = "https://openrouter.ai/api/v1";

    private String model = "deepseek/deepseek-v4.1-flash";

    /** DeepSeek 在 OpenRouter 上不开这个会先想 900+ token，首声明显变慢。 */
    private boolean reasoningEnabled = false;

    private String fallbackApiKey = "";

    private String fallbackApiKeyRef = "aiberm_online";

    private String fallbackBaseUrl = "https://aiberm.com/v1";

    private String fallbackModel = "deepseek-v4.1-flash";

    private int timeoutSeconds = 60;

    private int maxTurnSteps = 10;

    private int maxTokens = 1600;

    private double temperature = 0.7;

    /** 示意图由 LLM 生成 SVG 的上限；超时走模板/兜底卡片。 */
    private int diagramTimeoutSeconds = 25;

    private boolean diagramEnabled = true;
}
