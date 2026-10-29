package com.studyagent.service.application.verla.stt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Video Tutor 实时语音输入（DashScope fun-asr-realtime）。 */
@Data
@Component
@ConfigurationProperties(prefix = "verla.video-tutor.stt")
public class VideoTutorSttProperties {

    private boolean enabled = true;

    /** 留空时复用 TTS 的阿里云 key（同一把百炼 key）。 */
    private String apiKey = "";

    private String modelsYaml = "";

    private String apiKeyRef = "aliyun_bailian";

    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private String model = "fun-asr-realtime";

    /** 逗号分隔；默认中英混合。 */
    private String languageHints = "zh,en";

    private int connectTimeoutSeconds = 10;

    private int taskStartTimeoutSeconds = 10;
}
