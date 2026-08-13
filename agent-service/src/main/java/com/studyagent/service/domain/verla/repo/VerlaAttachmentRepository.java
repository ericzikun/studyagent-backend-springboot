package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaAttachment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Verla 附件仓储接口（V2）。
 * <p>
 * 详见 docs/V2/5.1 §3 / §6（上传链路：sign → upload → finalize → parse）。
 */
public interface VerlaAttachmentRepository {

    /** 创建：sign-url 阶段写入，status=UPLOADED 占位记录 */
    VerlaAttachment save(VerlaAttachment attachment);

    VerlaAttachment findById(Long id);

    VerlaAttachment findByObjectId(String objectId);

    List<VerlaAttachment> findByObjectIds(List<String> objectIds);

    List<VerlaAttachment> listByConversation(Long conversationId, int limit);

    List<VerlaAttachment> listByTurn(Long turnId);

    long countActiveUserUploadsForConversation(Long conversationId, LocalDateTime pendingCutoff);

    VerlaAttachment softDeleteUserUpload(String clerkUserId, String objectId);

    /**
     * 推进解析状态（按 objectId 幂等）：
     * <ul>
     *   <li>UPLOADED → PARSING（finalize 时由 Java 主动）；</li>
     *   <li>PARSING 阶段刷新 progress；</li>
     *   <li>PARSING → PARSED：补 summary / primaryArtifactUid / metaJson；</li>
     *   <li>PARSING → FAILED：补 parseError。</li>
     * </ul>
     * 返回更新后的最新记录；终态不可回退。
     */
    VerlaAttachment updateParseProgress(VerlaAttachment patch);

    /**
     * 上传落盘后合并字段（storageUri / checksum / turnId / status 等），不参与解析终态守卫。
     */
    VerlaAttachment updateByObjectIdSelective(VerlaAttachment patch);

    /**
     * 批量清理 Python 直传链路中 sign 后未 finalize 的 agent 输出预登记行：
     * 将 {@code attachment_origin = AGENT_OUTPUT}、仍停留在 {@code UPLOADED}
     * 且 storage_uri 仍为 {@code pending://}（未落盘）、创建时间早于 {@code cutoff} 的行，
     * 限 {@code batchSize} 条标记为 {@code FAILED} 并写入 {@code reason}。
     *
     * @return 本批实际更新的行数
     */
    int markStaleUploadedAgentOutputsFailed(LocalDateTime cutoff, int batchSize, String reason);

    /**
     * 批量取多个 conversation 的用户上传附件（USER_UPLOAD 且未删除），按 conversationId 分组。
     * admin 列表用；default 空实现供测试桩，生产实现覆盖。
     */
    default Map<Long, List<VerlaAttachment>> listUserUploadsByConversationIds(List<Long> conversationIds) {
        return Map.of();
    }
}
