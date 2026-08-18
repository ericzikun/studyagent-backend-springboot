package com.studyagent.infra.repository.emaillead;

import com.studyagent.infra.mapper.PublicEmailLeadMapper;
import com.studyagent.service.domain.emaillead.PublicEmailLeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 用 MySQL 唯一索引完成邮箱幂等写入，避免“先查再写”的并发窗口。
 */
@Repository
@RequiredArgsConstructor
public class PublicEmailLeadRepositoryImpl implements PublicEmailLeadRepository {

    private final PublicEmailLeadMapper mapper;

    @Override
    public boolean existsByNormalizedEmail(String normalizedEmail) {
        Integer result = mapper.existsByNormalizedEmail(normalizedEmail);
        return result != null && result > 0;
    }

    @Override
    public boolean insertIfAbsent(String normalizedEmail, String sourcePath, LocalDateTime createdAt) {
        return mapper.insertIfAbsent(normalizedEmail, sourcePath, createdAt) == 1;
    }
}
