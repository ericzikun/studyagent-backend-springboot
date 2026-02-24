package com.studyagent.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 检测响应 DTO（普通 POST）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanizerDetectResponse {
    /** AI 生成概率（0~1） */
    private Double probability;
    /** 标签：AI Generated / Human Written */
    private String label;
    /** 耗时（秒） */
    private Double elapsedSeconds;
}
