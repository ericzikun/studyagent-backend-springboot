package com.studyagent.api.dto.demo.note;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 博客语音合成结果：按段落切好的音频片段（URL 带 HMAC 签名）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteBlogTtsVO {

    private List<Segment> segments;
    private long totalDurationMs;
    private String voice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Segment {
        private int index;
        private int paragraphIndex;
        private String text;
        private String url;
        private long durationMs;
    }
}
