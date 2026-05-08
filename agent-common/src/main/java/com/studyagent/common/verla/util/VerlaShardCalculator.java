package com.studyagent.common.verla.util;

import lombok.experimental.UtilityClass;

import java.util.zip.CRC32;

/**
 * Verla MQ 分片计算
 * <p>
 * 公式：{@code shard = crc32(sessionId) % shardCount}
 * <p>
 * 必须与 Py 同学使用相同算法：CRC32 of decimal string of sessionId（UTF-8）
 * 对应文档 §5 / §20 D17
 */
@UtilityClass
public class VerlaShardCalculator {

    /**
     * 默认 shard 数（与文档 §20 D1 / application.yml 一致）
     */
    public static final int DEFAULT_SHARD_COUNT = 4;

    /**
     * 计算 sessionId 落到的 shard 编号
     *
     * @param sessionId 会话 id
     * @param shardCount 总 shard 数（必须 > 0）
     * @return 0..shardCount-1
     */
    public static int shardOf(long sessionId, int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be > 0, got " + shardCount);
        }
        CRC32 crc = new CRC32();
        crc.update(Long.toString(sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long v = crc.getValue();
        return (int) (v % shardCount);
    }

    /**
     * 两位零填充：4 -> "04"
     */
    public static String pad2(int shard) {
        return shard < 10 ? "0" + shard : String.valueOf(shard);
    }
}
