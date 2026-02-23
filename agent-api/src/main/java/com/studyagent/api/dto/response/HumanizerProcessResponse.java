package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本人性化改写响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanizerProcessResponse {
    /** 改写后的文本 */
    private String result;
    /** 耗时（秒） */
    private Double elapsedSeconds;
}
