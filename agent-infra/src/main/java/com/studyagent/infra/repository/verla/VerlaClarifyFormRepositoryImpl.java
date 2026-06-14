package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaClarifyFormEntity;
import com.studyagent.infra.mapper.verla.VerlaClarifyFormMapper;
import com.studyagent.service.domain.verla.VerlaClarifyForm;
import com.studyagent.service.domain.verla.repo.VerlaClarifyFormRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class VerlaClarifyFormRepositoryImpl
        extends ServiceImpl<VerlaClarifyFormMapper, VerlaClarifyFormEntity>
        implements VerlaClarifyFormRepository {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaClarifyForm upsertByFormId(VerlaClarifyForm form) {
        if (form == null || form.getFormId() == null || form.getFormId().isBlank()) {
            throw new IllegalArgumentException("form_id is required for upsertByFormId");
        }
        VerlaClarifyFormEntity existing = this.baseMapper.selectByFormId(form.getFormId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaClarifyFormEntity entity = new VerlaClarifyFormEntity()
                    .setFormId(form.getFormId())
                    .setConversationId(form.getConversationId())
                    .setTurnId(form.getTurnId())
                    .setSessionId(form.getSessionId())
                    .setMessageId(form.getMessageId())
                    .setTitle(form.getTitle())
                    .setDescription(form.getDescription())
                    .setSchemaJson(form.getSchemaJson())
                    .setStatus(form.getStatus() != null ? form.getStatus() : "OPEN")
                    .setExpiresAt(form.getExpiresAt())
                    .setSubmittedAt(form.getSubmittedAt())
                    .setSubmittedResponseId(form.getSubmittedResponseId())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        // OPEN 之外不允许 schema/description 等被覆盖
        if ("OPEN".equals(existing.getStatus())) {
            if (form.getSchemaJson() != null)  existing.setSchemaJson(form.getSchemaJson());
            if (form.getTitle() != null)       existing.setTitle(form.getTitle());
            if (form.getDescription() != null) existing.setDescription(form.getDescription());
            if (form.getExpiresAt() != null)   existing.setExpiresAt(form.getExpiresAt());
            if (form.getMessageId() != null)   existing.setMessageId(form.getMessageId());
        }
        existing.setUpdatedAt(now);
        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    @Override
    public VerlaClarifyForm findByFormId(String formId) {
        if (formId == null || formId.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByFormId(formId));
    }

    @Override
    public VerlaClarifyForm findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public List<VerlaClarifyForm> findOpenByConversation(Long conversationId) {
        return this.baseMapper.selectOpenByConversation(conversationId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<VerlaClarifyForm>> findOpenByConversationIds(List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = conversationIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return this.baseMapper.selectOpenByConversationIds(ids).stream()
                .map(this::toDomain)
                .collect(Collectors.groupingBy(VerlaClarifyForm::getConversationId, LinkedHashMap::new, Collectors.toList()));
    }

    @Override
    public int markSubmitted(String formId, Long submittedResponseId) {
        return this.baseMapper.markSubmitted(formId, submittedResponseId, LocalDateTime.now());
    }

    @Override
    public int markStatus(String formId, String newStatus) {
        return this.baseMapper.markStatus(formId, newStatus, LocalDateTime.now());
    }

    private VerlaClarifyForm toDomain(VerlaClarifyFormEntity e) {
        if (e == null) return null;
        return VerlaClarifyForm.builder()
                .id(e.getId())
                .formId(e.getFormId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .messageId(e.getMessageId())
                .title(e.getTitle())
                .description(e.getDescription())
                .schemaJson(e.getSchemaJson())
                .status(e.getStatus())
                .expiresAt(e.getExpiresAt())
                .submittedAt(e.getSubmittedAt())
                .submittedResponseId(e.getSubmittedResponseId())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
