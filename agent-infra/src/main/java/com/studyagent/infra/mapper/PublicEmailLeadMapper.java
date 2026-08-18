package com.studyagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.PublicEmailLeadEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 公开邮箱留资 Mapper。
 */
public interface PublicEmailLeadMapper extends BaseMapper<PublicEmailLeadEntity> {

    @Select("SELECT 1 FROM email_leads WHERE email_normalized = #{normalizedEmail} LIMIT 1")
    Integer existsByNormalizedEmail(@Param("normalizedEmail") String normalizedEmail);

    @Insert("""
            INSERT IGNORE INTO email_leads (
                email_normalized,
                source_path,
                created_at
            ) VALUES (
                #{normalizedEmail},
                #{sourcePath},
                #{createdAt}
            )
            """)
    int insertIfAbsent(
            @Param("normalizedEmail") String normalizedEmail,
            @Param("sourcePath") String sourcePath,
            @Param("createdAt") LocalDateTime createdAt);
}
