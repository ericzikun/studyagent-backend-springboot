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
}
