package com.studyagent.service.domain.humanizer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Humanizer 服务客户端接口（领域层定义）
 * <p>
 * 声明与 Python Humanizer/AI检测服务交互的方法契约。
 * 接口在 agent-service 定义，实现在 agent-infra 中通过 WebClient 调用 Python 服务。
 * <p>
 * 注意：SSE 流式接口（detectAIStream）不在此接口定义，
 * 因为领域层不应依赖 reactor/webflux。SSE 流式调用由 infra 层直接提供。
 */
public interface HumanizerServiceClient {

    /**
     * 文本人性化改写
     * 调用 Python 服务的 POST /process 端点
     *
     * @param text 待改写文本
     * @return 改写结果
     */
    HumanizerResult humanize(String text);

    /**
     * AI 检测（普通 POST，非 SSE）
     * 调用 Python 服务的 POST /predict 端点
     *
     * @param text 待检测文本
     * @return 检测结果
     */
    DetectResult detectAI(String text);

    /**
     * Humanizer 服务返回结果（领域模型）
     * 对应 Python /process 端点的响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class HumanizerResult {
        /** 响应码（200=成功） */
        private int code;
        /** 响应消息 */
        private String msg;
        /** 改写后的文本 */
        private String result;
        /** 耗时（秒） */
        private Double elapsedSeconds;
    }

    /**
     * AI 检测返回结果（领域模型）
     * 对应 Python /predict 端点的响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class DetectResult {
        /** 响应码（200=成功） */
        private int code;
        /** 响应消息（错误时有值） */
        private String msg;
        /** AI 生成概率（0~1） */
        private Double probability;
        /** 标签：AI Generated / Human Written */
        private String label;
        /** 耗时（秒） */
        private Double elapsedSeconds;
    }
}
