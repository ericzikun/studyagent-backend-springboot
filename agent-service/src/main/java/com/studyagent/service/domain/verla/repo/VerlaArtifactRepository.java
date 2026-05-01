package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaArtifact;

import java.util.List;

/**
 * Verla artifact 仓储接口（V2 扩展）。
 * <p>
 * 读路径：详情（id / artifactUid） / 按 conversation / session 列举。
 * 写路径：upsertByUid（V2 由 {@code AGENT_ARTIFACT_UPDATED} 事件驱动，按 artifact_uid 幂等）。
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §13.4 与 docs/V2/5.1 §3 / §5。
 */
public interface VerlaArtifactRepository {

    VerlaArtifact findById(Long id);

    /** V2: 按业务唯一 ID 查（前端 / hydrate / event handler 都用） */
    VerlaArtifact findByUid(String artifactUid);

    List<VerlaArtifact> findByConversation(Long conversationId);

    List<VerlaArtifact> findBySession(Long sessionId);

    /** V2: 批量按 uid 查（hydrate 注入上下文用） */
    List<VerlaArtifact> findByUids(List<String> artifactUids);

    /**
     * V2: 按 {@code artifactUid} 幂等 upsert。
     * <ul>
     *   <li>不存在则插入；version 缺省 1。</li>
     *   <li>存在则按"高 version 覆盖低 version"策略增量更新；返回最新值。</li>
     * </ul>
     */
    VerlaArtifact upsertByUid(VerlaArtifact artifact);
}
