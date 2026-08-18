package com.studyagent.service.domain.emaillead;

import java.time.LocalDateTime;

/**
 * 公开邮箱线索的最小持久化边界。
 */
public interface PublicEmailLeadRepository {

    /** 查询邮箱是否已经落库，用于避免重复邮箱竞争每日新增预算。 */
    boolean existsByNormalizedEmail(String normalizedEmail);

    /**
     * 首次出现的邮箱写入来源；重复邮箱保持原记录不变。
     *
     * @return true 表示本次新建，false 表示邮箱已存在
     */
    boolean insertIfAbsent(String normalizedEmail, String sourcePath, LocalDateTime createdAt);
}
