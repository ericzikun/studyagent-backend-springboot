package com.studyagent.service.domain.verla.repo;

import com.studyagent.service.domain.verla.AiWritingHumanizerResult;

import java.util.List;

public interface AiWritingHumanizerResultRepository {

    /**
     * 按 artifact_uid 幂等写入；已存在则忽略。
     */
    void insertIgnoreByArtifactUid(AiWritingHumanizerResult row);

    boolean existsByUserAndHash(String clerkUserId, String resultHash);

    List<AiWritingHumanizerResult> listRecentByUser(String clerkUserId, int limit);
}
