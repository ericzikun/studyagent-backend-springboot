package com.studyagent.common.verla.util;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verla correlationId 生成 / 解析
 * <p>
 * 标准格式：{@code conv:{c}:turn:{t}:sess:{s}}
 * 对应文档 §6 / §20 D13
 */
@UtilityClass
public class VerlaCorrelationId {

    private static final Pattern PATTERN =
            Pattern.compile("^conv:(\\d+):turn:(\\d+):sess:(\\d+)$");

    /** 拼接成标准 correlationId */
    public static String of(long conversationId, long turnId, long sessionId) {
        return "conv:" + conversationId + ":turn:" + turnId + ":sess:" + sessionId;
    }

    /** 校验格式合法性 */
    public static boolean isValid(String correlationId) {
        return StringUtils.isNotBlank(correlationId) && PATTERN.matcher(correlationId).matches();
    }

    /**
     * 解析 correlationId 三元组
     * @throws IllegalArgumentException 当格式不合法
     */
    public static Triple parse(String correlationId) {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId is null");
        }
        Matcher m = PATTERN.matcher(correlationId);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid verla correlationId: " + correlationId);
        }
        return new Triple(
                Long.parseLong(m.group(1)),
                Long.parseLong(m.group(2)),
                Long.parseLong(m.group(3))
        );
    }

    /** 拼接 orderingKey：session:{sessionId} */
    public static String orderingKey(long sessionId) {
        return "session:" + sessionId;
    }

    public record Triple(long conversationId, long turnId, long sessionId) {}
}
