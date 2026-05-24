package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infra.entity.verla.VerlaWorkforceTaskOutputEntity;
import com.studyagent.infra.mapper.verla.VerlaWorkforceTaskOutputMapper;
import com.studyagent.service.domain.verla.VerlaWorkforceTaskOutput;
import com.studyagent.service.domain.verla.repo.VerlaWorkforceTaskOutputRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class VerlaWorkforceTaskOutputRepositoryImpl
        extends ServiceImpl<VerlaWorkforceTaskOutputMapper, VerlaWorkforceTaskOutputEntity>
        implements VerlaWorkforceTaskOutputRepository {

    private final ObjectMapper objectMapper;

    @Override
    public Optional<VerlaWorkforceTaskOutput> findBySessionAndNode(Long sessionId, String nodeId) {
        return Optional.ofNullable(
                this.baseMapper.selectBySessionAndNode(sessionId, nodeId)).map(this::toDomain);
    }

    @Override
    public List<VerlaWorkforceTaskOutput> listBySession(Long sessionId) {
        return this.baseMapper.selectBySession(sessionId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaWorkforceTaskOutput upsertBySessionNode(VerlaWorkforceTaskOutput patch) {
        if (patch.getSessionId() == null || patch.getNodeId() == null) {
            throw new IllegalArgumentException("sessionId and nodeId are required for upsertBySessionNode");
        }
        VerlaWorkforceTaskOutputEntity existing =
                this.baseMapper.selectBySessionAndNode(patch.getSessionId(), patch.getNodeId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaWorkforceTaskOutputEntity entity = new VerlaWorkforceTaskOutputEntity()
                    .setConversationId(patch.getConversationId())
                    .setTurnId(patch.getTurnId())
                    .setSessionId(patch.getSessionId())
                    .setNodeId(patch.getNodeId())
                    .setResultText(patch.getResultText())
                    .setDetailItemsJson(patch.getDetailItemsJson())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        // resultText: 追加（非空才追加）
        if (patch.getResultText() != null && !patch.getResultText().isEmpty()) {
            String prev = existing.getResultText();
            existing.setResultText(prev != null ? prev + patch.getResultText() : patch.getResultText());
        }

        // detailItemsJson: JSON 数组合并（非空才追加）
        if (patch.getDetailItemsJson() != null && !patch.getDetailItemsJson().isBlank()) {
            existing.setDetailItemsJson(mergeJsonArrays(existing.getDetailItemsJson(), patch.getDetailItemsJson()));
        }

        existing.setUpdatedAt(now);
        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    private String mergeJsonArrays(String existingJson, String incomingJson) {
        try {
            TypeReference<List<Map<String, Object>>> listType = new TypeReference<>() {};
            List<Map<String, Object>> merged = new ArrayList<>();
            if (existingJson != null && !existingJson.isBlank()) {
                merged.addAll(objectMapper.readValue(existingJson, listType));
            }
            merged.addAll(objectMapper.readValue(incomingJson, listType));
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("[Verla/workforce] detailItemsJson merge failed, overwriting: {}", e.getMessage());
            return incomingJson;
        }
    }

    private VerlaWorkforceTaskOutput toDomain(VerlaWorkforceTaskOutputEntity e) {
        if (e == null) return null;
        return VerlaWorkforceTaskOutput.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .nodeId(e.getNodeId())
                .resultText(e.getResultText())
                .detailItemsJson(e.getDetailItemsJson())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
