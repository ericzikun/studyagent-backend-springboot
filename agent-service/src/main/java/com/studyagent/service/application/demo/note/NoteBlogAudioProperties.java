package com.studyagent.service.application.demo.note;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 笔记博客语音的本地缓存与签名 URL 配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "verla.note.blog-audio")
public class NoteBlogAudioProperties {

    private boolean enabled = true;

    /** 相对路径按进程工作目录解析；容器里就是 /app/storage/note_blog_audio。 */
    private String dir = "storage/note_blog_audio";

    /** 为空时启动随机生成（重启后旧 URL 失效，重新播放会重新签名）。 */
    private String secret = "";

    /** 签名有效期：默认 2 小时，覆盖一篇博客的连续播放。 */
    private int signTtlSeconds = 7200;

    /** 单篇博客最多合成多少段，避免异常长文打爆 TTS 网关。 */
    private int maxSegments = 40;

    /** 单段朗读字数上限（CosyVoice 侧建议 <= 280）。 */
    private int maxCharsPerSegment = 220;

    /**
     * 播客主持人音色（女）。独立于 video-tutor 的 Theo/Nora，
     * 改播客音色不会影响白板老师。
     */
    private String hostVoice = "longanhuan_v3";

    /** 播客讲解人音色（男）。 */
    private String guestVoice = "longanzhi_v3";

    /** 预生成后台任务线程数；每个任务负责一篇博客的分段上传。 */
    private int workerCount = 2;

    /** 预生成任务队列容量；满了直接置 FAILED，交给前端按需合成兜底。 */
    private int queueCapacity = 200;

    /** 全局 TTS 合成并发（所有预生成/按需请求共享）。 */
    private int synthConcurrency = 4;

    /** 共享合成线程池的排队上限；超出视为服务繁忙。 */
    private int synthQueueCapacity = 256;

    /** PROCESSING 超过多少分钟视为任务已丢失，回收为 FAILED。 */
    private int staleProcessingMinutes = 120;

    /** 回收器首次/周期执行间隔（毫秒）。 */
    private long reaperInitialDelayMs = 60_000L;
    private long reaperIntervalMs = 600_000L;
}
