package com.studyagent.infra.repository.verla;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.verla.enums.VerlaToolStatus;
import com.studyagent.common.verla.enums.VerlaToolVisibility;
import com.studyagent.infra.entity.verla.VerlaToolCallEntity;
import com.studyagent.infra.mapper.verla.VerlaToolCallMapper;
import com.studyagent.service.domain.verla.VerlaToolCall;
import com.studyagent.service.domain.verla.repo.VerlaToolCallRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class VerlaToolCallRepositoryImpl
        extends ServiceImpl<VerlaToolCallMapper, VerlaToolCallEntity>
        implements VerlaToolCallRepository {

    @Override
    public VerlaToolCall findByCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        return toDomain(this.baseMapper.selectByCallId(toolCallId));
    }

    @Override
    public List<VerlaToolCall> listByTurn(Long turnId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return this.baseMapper.selectByTurn(turnId, safe)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaToolCall> listBySession(Long sessionId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return this.baseMapper.selectBySession(sessionId, safe)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<VerlaToolCall> listVisibleByConversation(Long conversationId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return this.baseMapper.selectVisibleByConversation(conversationId, safe)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerlaToolCall upsertByCallId(VerlaToolCall patch) {
        if (patch == null || patch.getToolCallId() == null || patch.getToolCallId().isBlank()) {
            throw new IllegalArgumentException("tool_call_id is required for upsertByCallId");
        }
        VerlaToolCallEntity existing = this.baseMapper.selectByCallId(patch.getToolCallId());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            VerlaToolCallEntity entity = new VerlaToolCallEntity()
                    .setToolCallId(patch.getToolCallId())
                    .setConversationId(patch.getConversationId())
                    .setTurnId(patch.getTurnId())
                    .setSessionId(patch.getSessionId())
                    .setStepId(patch.getStepId())
                    .setParentCallId(patch.getParentCallId())
                    .setAgentName(patch.getAgentName())
                    .setToolName(patch.getToolName())
                    .setStatus(patch.getStatus() != null ? patch.getStatus() : VerlaToolStatus.PENDING.name())
                    .setVisibility(patch.getVisibility() != null ? patch.getVisibility() : VerlaToolVisibility.INTERNAL.name())
                    .setToolInputJson(patch.getToolInputJson())
                    .setToolOutputJson(patch.getToolOutputJson())
                    .setSummary(patch.getSummary())
                    .setErrorCode(patch.getErrorCode())
                    .setErrorMessage(patch.getErrorMessage())
                    .setStartedAt(patch.getStartedAt() != null ? patch.getStartedAt() : now)
                    .setFinishedAt(patch.getFinishedAt())
                    .setDurationMs(patch.getDurationMs())
                    .setMetaJson(patch.getMetaJson())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            this.baseMapper.insert(entity);
            return toDomain(entity);
        }

        // 终态不可回退
        VerlaToolStatus existingStatus = safeStatus(existing.getStatus());
        VerlaToolStatus incomingStatus = safeStatus(patch.getStatus());
        if (existingStatus != null && existingStatus.isTerminal()
                && incomingStatus != null && !incomingStatus.isTerminal()) {
            // 已是终态，忽略后续中间态推进
            return toDomain(existing);
        }

        if (patch.getStatus() != null)         existing.setStatus(patch.getStatus());
        if (patch.getVisibility() != null)     existing.setVisibility(patch.getVisibility());
        if (patch.getStepId() != null)         existing.setStepId(patch.getStepId());
        if (patch.getParentCallId() != null)   existing.setParentCallId(patch.getParentCallId());
        if (patch.getAgentName() != null)      existing.setAgentName(patch.getAgentName());
        if (patch.getToolName() != null)       existing.setToolName(patch.getToolName());
        if (patch.getToolInputJson() != null)  existing.setToolInputJson(patch.getToolInputJson());
        if (patch.getToolOutputJson() != null) existing.setToolOutputJson(patch.getToolOutputJson());
        if (patch.getSummary() != null)        existing.setSummary(patch.getSummary());
        if (patch.getErrorCode() != null)      existing.setErrorCode(patch.getErrorCode());
        if (patch.getErrorMessage() != null)   existing.setErrorMessage(patch.getErrorMessage());
        if (patch.getStartedAt() != null)      existing.setStartedAt(patch.getStartedAt());
        if (patch.getFinishedAt() != null)     existing.setFinishedAt(patch.getFinishedAt());
        if (patch.getDurationMs() != null)     existing.setDurationMs(patch.getDurationMs());
        if (patch.getMetaJson() != null)       existing.setMetaJson(patch.getMetaJson());
        existing.setUpdatedAt(now);

        this.baseMapper.updateById(existing);
        return toDomain(existing);
    }

    private VerlaToolStatus safeStatus(String s) {
        if (s == null) {
            return null;
        }
        try {
            return VerlaToolStatus.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private VerlaToolCall toDomain(VerlaToolCallEntity e) {
        if (e == null) {
            return null;
        }
        return VerlaToolCall.builder()
                .id(e.getId())
                .toolCallId(e.getToolCallId())
                .conversationId(e.getConversationId())
                .turnId(e.getTurnId())
                .sessionId(e.getSessionId())
                .stepId(e.getStepId())
                .parentCallId(e.getParentCallId())
                .agentName(e.getAgentName())
                .toolName(e.getToolName())
                .status(e.getStatus())
                .visibility(e.getVisibility())
                .toolInputJson(e.getToolInputJson())
                .toolOutputJson(e.getToolOutputJson())
                .summary(e.getSummary())
                .errorCode(e.getErrorCode())
                .errorMessage(e.getErrorMessage())
                .startedAt(e.getStartedAt())
                .finishedAt(e.getFinishedAt())
                .durationMs(e.getDurationMs())
                .metaJson(e.getMetaJson())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
