package com.studyagent.service.application.verla.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Py 侧 Context API 可选开关（V2）。
 * <p>
 * 对应 docs/V2/Verla Message 上下文与 Tool Trace 设计方案.md §6
 * 与 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §5.2。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionContextQueryOptions {

    /** 返回完整 tool trace（含 DB 中已截断的 IO JSON） */
    @Builder.Default
    private boolean includeTrace = false;

    /** 返回 USER_VISIBLE 工具的压缩摘要列表 */
    @Builder.Default
    private boolean includeToolSummaries = false;

    /** 返回 conversation 级 artifact 列表（按更新时间倒序，服务端再 cap） */
    @Builder.Default
    private boolean includeArtifacts = true;

    /** 最近消息条数上限；null 表示走 {@code verla.context-cache.recent-message-limit} */
    private Integer messageLimit;

    /** tool trace / summaries 条数上限；null 表示走 {@code verla.context-cache.trace-limit-default} */
    private Integer traceLimit;

    public static VerlaSessionContextQueryOptions defaults() {
        return VerlaSessionContextQueryOptions.builder().build();
    }
}
