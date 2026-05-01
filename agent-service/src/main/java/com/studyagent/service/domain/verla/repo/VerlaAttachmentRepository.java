package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaAttachment;

import java.util.List;

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
}
