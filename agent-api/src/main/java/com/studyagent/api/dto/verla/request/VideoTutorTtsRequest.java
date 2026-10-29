package com.studyagent.api.dto.verla.request;

import lombok.Data;

/**
 * 按拍朗读请求。文本就是这一拍的口播稿；Java 直接合成并回 PCM 流。
 */
@Data
public class VideoTutorTtsRequest {

    private String text;

    /** 0.5–2.0；缺省 1.0。 */
    private Double speed;

    /** 预留：当前音色是中文，前端仍会把 UI 语言带上来。 */
    private String language;
}
