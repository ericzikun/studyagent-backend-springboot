package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.verla.VerlaClarifyResponseEntity;
import com.studyagent.infra.mapper.verla.VerlaClarifyResponseMapper;
import com.studyagent.service.domain.verla.VerlaClarifyResponse;
import com.studyagent.service.domain.verla.repo.VerlaClarifyResponseRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaClarifyResponseRepositoryImpl
        extends ServiceImpl<VerlaClarifyResponseMapper, VerlaClarifyResponseEntity>
        implements VerlaClarifyResponseRepository {

    @Override
    public VerlaClarifyResponse save(VerlaClarifyResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        VerlaClarifyResponseEntity entity = toEntity(response);
        if (entity.getSubmittedAt() == null) {
            entity.setSubmittedAt(LocalDateTime.now());
        }
        this.save(entity);
        response.setId(entity.getId());
        response.setSubmittedAt(entity.getSubmittedAt());
        return response;
    }

    @Override
    public VerlaClarifyResponse findById(Long id) {
        return toDomain(this.getById(id));
    }

    @Override
    public VerlaClarifyResponse findByResponseUid(String responseUid) {
        if (responseUid == null || responseUid.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByResponseUid(responseUid));
    }

    @Override
    public List<VerlaClarifyResponse> findByFormId(String formId) {
        return this.baseMapper.selectByFormId(formId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    private VerlaClarifyResponse toDomain(VerlaClarifyResponseEntity e) {
        if (e == null) return null;
        return VerlaClarifyResponse.builder()
                .id(e.getId())
                .responseUid(e.getResponseUid())
                .formId(e.getFormId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .userId(e.getUserId())
                .answersJson(e.getAnswersJson())
                .submittedAt(e.getSubmittedAt())
                .build();
    }

    private VerlaClarifyResponseEntity toEntity(VerlaClarifyResponse d) {
        return new VerlaClarifyResponseEntity()
                .setId(d.getId())
                .setResponseUid(d.getResponseUid())
                .setFormId(d.getFormId())
                .setConversationId(d.getConversationId())
                .setTurnId(d.getTurnId())
                .setUserId(d.getUserId())
                .setAnswersJson(d.getAnswersJson())
                .setSubmittedAt(d.getSubmittedAt());
    }
}
