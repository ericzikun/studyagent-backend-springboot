package com.studyagent.common.verla.util;

import com.studyagent.common.verla.enums.VerlaAgentEventType;
import lombok.experimental.UtilityClass;

/**
 * Verla MQ routing key 生成
 * <p>
 * 命名规范见文档 §5 / §6.2：
 * <ul>
 *   <li>Py→Java 事件： {@code verla.event.s{shard:02d}.{eventType.toLowerSnake}}</li>
 *   <li>Java→Py 命令： 沿用 {@link com.studyagent.common.verla.enums.VerlaCommandAction#getCode()}</li>
 * </ul>
 */
@UtilityClass
public class VerlaRoutingKey {

    /** 事件 routing key 前缀 */
    public static final String EVENT_PREFIX = "verla.event.s";

    /**
     * 事件 routing key
     */
    public static String forEvent(VerlaAgentEventType type, int shard) {
        return EVENT_PREFIX + VerlaShardCalculator.pad2(shard) + "." + toDotLower(type.name());
    }

    /**
     * 事件 routing key（直接传 enum name 字符串，便于 Py 同学日志对比）
     */
    public static String forEvent(String eventTypeName, int shard) {
        return EVENT_PREFIX + VerlaShardCalculator.pad2(shard) + "." + toDotLower(eventTypeName);
    }

    /**
     * 队列名：{@code verla.event.s{shard:02d}}
     */
    public static String queueOfShard(int shard) {
        return EVENT_PREFIX + VerlaShardCalculator.pad2(shard);
    }

    /**
     * 队列绑定通配符：{@code verla.event.s{shard:02d}.#}
     */
    public static String bindingPatternOfShard(int shard) {
        return EVENT_PREFIX + VerlaShardCalculator.pad2(shard) + ".#";
    }

    /**
     * AGENT_STEP_STREAM_CHUNK -> agent.step.stream_chunk
     */
    private static String toDotLower(String enumName) {
        return enumName.toLowerCase().replace('_', '.');
    }
}
