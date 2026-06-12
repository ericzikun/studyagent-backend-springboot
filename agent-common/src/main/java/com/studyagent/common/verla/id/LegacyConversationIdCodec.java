package com.studyagent.common.verla.id;

/**
 * 1.0 历史任务在 V2 conversation id 数轴上的虚拟编码。
 * <p>
 * 设计要点（见 docs/02-架构稳定性分析/11-1.0到2.0轻量API兼容方案.md §三）：
 * <ul>
 *     <li>为 1.0 {@code tasks.id} 划一段高位偏移区间（{@link #LEGACY_OFFSET}），
 *         使其内部 Long cid 与真实 {@code verla_conversations.id} 互不冲突。</li>
 *     <li>对外仍走 V2 既有的 public id 体系：虚拟内部 cid 经
 *         {@link VerlaPublicIdCodec#encode}（CONVERSATION 前缀 + Sqids）编码为 {@code vc_xxx} 返给前端；
 *         前端回传后由 {@code @VerlaPublicId} resolver 解码回该虚拟内部 cid，
 *         此处再用 {@link #isLegacy(Long)} 判定是否走 1.0 适配链路。</li>
 * </ul>
 * 因此本类只负责"虚拟内部 cid &lt;-&gt; legacy taskId"的纯数值映射，不感知 Sqids 编码。
 */
public final class LegacyConversationIdCodec {

    /** 1.0 任务在 V2 cid 空间的起点；远高于 verla_conversations 当前自增值。 */
    public static final long LEGACY_OFFSET = 10_000_000_000L; // 100 亿

    private LegacyConversationIdCodec() {
    }

    /** legacy taskId -> 虚拟内部 cid。 */
    public static long encode(long legacyTaskId) {
        return LEGACY_OFFSET + legacyTaskId;
    }

    /** 虚拟内部 cid -> legacy taskId。 */
    public static long decode(long virtualCid) {
        return virtualCid - LEGACY_OFFSET;
    }

    /** 判定一个（已由 resolver 解码出的）内部 cid 是否落在 1.0 虚拟区间。 */
    public static boolean isLegacy(Long internalCid) {
        return internalCid != null && internalCid >= LEGACY_OFFSET;
    }
}
