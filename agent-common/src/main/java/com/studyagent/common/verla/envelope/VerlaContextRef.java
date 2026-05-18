package com.studyagent.common.verla.envelope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命令 payload 中的 contextRef 指针：让 Py 根据版本号反查 Java 内部接口拿上下文
 * <p>
 * 对应文档 §6.1 / §10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaContextRef {

    /**
     * 引用类型，默认 internal-rpc（HTTP 调 /internal/verla/sessions/{sid}/context）
     */
    @Builder.Default
    private String type = "internal-rpc";

    /**
     * 拉取上下文的相对路径
     */
    private String endpoint;

    /**
     * conversation 维度的版本号（Redis 缓存 key）
     */
    private Long convVersion;

    /**
     * turn 维度的版本号
     */
    private Long turnVersion;
}
