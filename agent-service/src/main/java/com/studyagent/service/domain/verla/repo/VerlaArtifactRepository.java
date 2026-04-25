package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.VerlaArtifact;

import java.util.List;

/**
 * Verla artifact 仓储接口
 * <p>
 * MVP 仅做 read：详情 / 按 conversation 列举（前端右栏材料）。\
 * 写入路径（增量 patch + version 自增）由 PR-21 的 VerlaArtifactHandler 负责，\
 * 届时再追加 saveOrUpdate 等方法。\
 */
public interface VerlaArtifactRepository {

    VerlaArtifact findById(Long id);

    List<VerlaArtifact> findByConversation(Long conversationId);

    List<VerlaArtifact> findBySession(Long sessionId);
}
