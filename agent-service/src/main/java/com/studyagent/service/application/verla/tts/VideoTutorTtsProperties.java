package com.studyagent.service.application.verla.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Video Tutor 的 Java 直连 TTS 配置。
 *
 * 复刻 ahaboard-clone 的播放体验：前端按拍请求音频，Java 把百炼 CosyVoice 的
 * PCM 流原样透传，不再经过 Python 合成 / OSS / MQ。
 */
@Data
@Component
@ConfigurationProperties(prefix = "verla.video-tutor.tts")
public class VideoTutorTtsProperties {

    private boolean enabled = true;

    /**
     * 显式密钥。留空时从 {@link #modelsYaml} 的 api_keys.{@link #apiKeyRef} 读取，
     * 与 Python 侧共用同一份 models.yaml，避免再维护一套密钥。
     */
    private String apiKey = "";

    /** models.yaml 路径；支持 MODELS_YAML 同款环境变量注入。 */
    private String modelsYaml = "";

    private String apiKeyRef = "aliyun_bailian";

    /** ⚠️ workspace id 带 llm- 前缀，去掉会 IllegalEndpoint。 */
    private String wsUrl = "wss://llm-nuotcmidonohnwwc.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference";

    private String model = "cosyvoice-v3-flash";

    /** Theo 的音色（clone 默认）。 */
    private String voice = "longanhuan_v3";

    /** Nora 的音色；前端字幕栏可切换。 */
    private String voiceNora = "longxiaochun_v3";

    private int sampleRate = 24000;

    /** 建连 + task-started 的上限。 */
    private int connectTimeoutSeconds = 10;

    /** 等第一块音频的上限：首块回来才向浏览器发响应头，失败还能干净地 502。 */
    private int firstAudioTimeoutSeconds = 12;

    /** 流中途等下一块的上限。 */
    private int readTimeoutSeconds = 30;

    /** 同时合成的拍数上限，避免一轮 24 步瞬时打满网关。 */
    private int maxConcurrency = 4;

    /** 单次朗读的最大字数，和 clone 的 to_spoken 截断口径一致。 */
    private int maxChars = 280;

    private String dataInspection = "enable";
}
