package com.studyagent.infra.repository.demo.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.infra.entity.demo.learning.DemoLearningAgentStateEntity;
import com.studyagent.infra.entity.demo.learning.DemoLearningEdgeEntity;
import com.studyagent.infra.entity.demo.learning.DemoLearningMessageEntity;
import com.studyagent.infra.entity.demo.learning.DemoLearningNodeEntity;
import com.studyagent.infra.entity.demo.learning.DemoLearningThemeEntity;
import com.studyagent.infra.entity.demo.learning.DemoLearningUserProfileEntity;
import com.studyagent.infra.mapper.demo.learning.DemoLearningAgentStateMapper;
import com.studyagent.infra.mapper.demo.learning.DemoLearningEdgeMapper;
import com.studyagent.infra.mapper.demo.learning.DemoLearningMessageMapper;
import com.studyagent.infra.mapper.demo.learning.DemoLearningNodeMapper;
import com.studyagent.infra.mapper.demo.learning.DemoLearningThemeMapper;
import com.studyagent.infra.mapper.demo.learning.DemoLearningUserProfileMapper;
import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningEdge;
import com.studyagent.service.domain.demo.learning.DemoLearningMessage;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.DemoLearningUserProfile;
import com.studyagent.service.domain.demo.learning.repo.DemoLearningCanvasRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Learning Canvas Demo 仓储实现（只新增，不触碰旧仓储/旧表）。
 */
@Repository
public class DemoLearningCanvasRepositoryImpl implements DemoLearningCanvasRepository {

    private final DemoLearningThemeMapper themeMapper;
    private final DemoLearningNodeMapper nodeMapper;
    private final DemoLearningEdgeMapper edgeMapper;
    private final DemoLearningMessageMapper messageMapper;
    private final DemoLearningAgentStateMapper agentStateMapper;
    private final DemoLearningUserProfileMapper userProfileMapper;

    public DemoLearningCanvasRepositoryImpl(
            DemoLearningThemeMapper themeMapper,
            DemoLearningNodeMapper nodeMapper,
            DemoLearningEdgeMapper edgeMapper,
            DemoLearningMessageMapper messageMapper,
            DemoLearningAgentStateMapper agentStateMapper,
            DemoLearningUserProfileMapper userProfileMapper) {
        this.themeMapper = themeMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.messageMapper = messageMapper;
        this.agentStateMapper = agentStateMapper;
        this.userProfileMapper = userProfileMapper;
    }

    // ================= theme =================

    @Override
    public DemoLearningTheme saveTheme(DemoLearningTheme theme) {
        DemoLearningThemeEntity e = toThemeEntity(theme);
        if (e.getId() == null) {
            themeMapper.insert(e);
        } else {
            themeMapper.updateById(e);
        }
        theme.setId(e.getId());
        return theme;
    }

    @Override
    public DemoLearningTheme findThemeById(Long id) {
        DemoLearningThemeEntity e = themeMapper.selectById(id);
        return e == null ? null : toThemeDomain(e);
    }

    @Override
    public DemoLearningTheme findThemeByIdAndUser(Long id, String clerkUserId) {
        DemoLearningThemeEntity e = themeMapper.selectOne(
                new LambdaQueryWrapper<DemoLearningThemeEntity>()
                        .eq(DemoLearningThemeEntity::getId, id)
                        .eq(DemoLearningThemeEntity::getClerkUserId, clerkUserId)
                        .last("LIMIT 1"));
        return e == null ? null : toThemeDomain(e);
    }

    @Override
    public List<DemoLearningTheme> listThemesByUser(String clerkUserId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return themeMapper.selectList(
                        new LambdaQueryWrapper<DemoLearningThemeEntity>()
                                .eq(DemoLearningThemeEntity::getClerkUserId, clerkUserId)
                                .orderByDesc(DemoLearningThemeEntity::getLastSavedAt)
                                .last("LIMIT " + lim))
                .stream().map(this::toThemeDomain).collect(Collectors.toList());
    }

    @Override
    public void touchThemeSavedAt(Long id) {
        DemoLearningThemeEntity e = new DemoLearningThemeEntity();
        e.setId(id);
        e.setLastSavedAt(java.time.LocalDateTime.now());
        themeMapper.updateById(e);
    }

    // ================= node =================

    @Override
    public DemoLearningNode saveNode(DemoLearningNode node) {
        DemoLearningNodeEntity e = toNodeEntity(node);
        if (e.getId() == null) {
            nodeMapper.insert(e);
        } else {
            nodeMapper.updateById(e);
        }
        node.setId(e.getId());
        return node;
    }

    @Override
    public DemoLearningNode findNodeById(Long id) {
        DemoLearningNodeEntity e = nodeMapper.selectById(id);
        return e == null ? null : toNodeDomain(e);
    }

    @Override
    public List<DemoLearningNode> listNodesByTheme(Long themeId) {
        return nodeMapper.selectList(
                        new LambdaQueryWrapper<DemoLearningNodeEntity>()
                                .eq(DemoLearningNodeEntity::getThemeId, themeId)
                                .orderByAsc(DemoLearningNodeEntity::getId))
                .stream().map(this::toNodeDomain).collect(Collectors.toList());
    }

    @Override
    public List<DemoLearningNode> listKnowledgeNodesByTheme(Long themeId) {
        return nodeMapper.selectList(
                        new LambdaQueryWrapper<DemoLearningNodeEntity>()
                                .eq(DemoLearningNodeEntity::getThemeId, themeId)
                                .eq(DemoLearningNodeEntity::getNodeType, "knowledge")
                                .orderByAsc(DemoLearningNodeEntity::getId))
                .stream().map(this::toNodeDomain).collect(Collectors.toList());
    }

    @Override
    public boolean deleteNode(Long id) {
        return nodeMapper.deleteById(id) > 0;
    }

    // ================= edge =================

    @Override
    public DemoLearningEdge saveEdge(DemoLearningEdge edge) {
        DemoLearningEdgeEntity e = toEdgeEntity(edge);
        if (e.getId() == null) {
            edgeMapper.insert(e);
        } else {
            edgeMapper.updateById(e);
        }
        edge.setId(e.getId());
        return edge;
    }

    @Override
    public List<DemoLearningEdge> listEdgesByTheme(Long themeId) {
        return edgeMapper.selectList(
                        new LambdaQueryWrapper<DemoLearningEdgeEntity>()
                                .eq(DemoLearningEdgeEntity::getThemeId, themeId)
                                .orderByAsc(DemoLearningEdgeEntity::getId))
                .stream().map(this::toEdgeDomain).collect(Collectors.toList());
    }

    // ================= message =================

    @Override
    public DemoLearningMessage saveMessage(DemoLearningMessage message) {
        DemoLearningMessageEntity e = toMessageEntity(message);
        messageMapper.insert(e);
        message.setId(e.getId());
        return message;
    }

    @Override
    public List<DemoLearningMessage> listMessagesByTheme(Long themeId) {
        return messageMapper.selectList(
                        new LambdaQueryWrapper<DemoLearningMessageEntity>()
                                .eq(DemoLearningMessageEntity::getThemeId, themeId)
                                .orderByAsc(DemoLearningMessageEntity::getId))
                .stream().map(this::toMessageDomain).collect(Collectors.toList());
    }

    @Override
    public void appendToLastAssistantMessage(Long themeId, String appendContent) {
        DemoLearningMessageEntity last = messageMapper.selectOne(
                new LambdaQueryWrapper<DemoLearningMessageEntity>()
                        .eq(DemoLearningMessageEntity::getThemeId, themeId)
                        .eq(DemoLearningMessageEntity::getRole, "assistant")
                        .orderByDesc(DemoLearningMessageEntity::getId)
                        .last("LIMIT 1"));
        if (last != null) {
            last.setContent((last.getContent() == null ? "" : last.getContent()) + appendContent);
            messageMapper.updateById(last);
        }
    }

    // ================= agent state =================

    @Override
    public DemoLearningAgentState getAgentState(Long themeId) {
        DemoLearningAgentStateEntity e = agentStateMapper.selectById(themeId);
        return e == null ? null : toAgentStateDomain(e);
    }

    @Override
    public void saveAgentState(DemoLearningAgentState state) {
        DemoLearningAgentStateEntity e = toAgentStateEntity(state);
        DemoLearningAgentStateEntity existing = agentStateMapper.selectById(state.getThemeId());
        if (existing == null) {
            agentStateMapper.insert(e);
        } else {
            agentStateMapper.updateById(e);
        }
    }

    // ================= user profile =================

    @Override
    public DemoLearningUserProfile getUserProfile(String clerkUserId) {
        DemoLearningUserProfileEntity e = userProfileMapper.selectOne(
                new LambdaQueryWrapper<DemoLearningUserProfileEntity>()
                        .eq(DemoLearningUserProfileEntity::getClerkUserId, clerkUserId)
                        .last("LIMIT 1"));
        return e == null ? null : toUserProfileDomain(e);
    }

    @Override
    public void saveUserProfile(DemoLearningUserProfile profile) {
        DemoLearningUserProfileEntity e = toUserProfileEntity(profile);
        DemoLearningUserProfileEntity existing = userProfileMapper.selectOne(
                new LambdaQueryWrapper<DemoLearningUserProfileEntity>()
                        .eq(DemoLearningUserProfileEntity::getClerkUserId, profile.getClerkUserId())
                        .last("LIMIT 1"));
        if (existing != null) {
            e.setId(existing.getId());
            userProfileMapper.updateById(e);
        } else {
            userProfileMapper.insert(e);
        }
    }

    // ================= converters =================

    private DemoLearningThemeEntity toThemeEntity(DemoLearningTheme d) {
        DemoLearningThemeEntity e = new DemoLearningThemeEntity();
        e.setId(d.getId());
        e.setClerkUserId(d.getClerkUserId());
        e.setInitialQuery(d.getInitialQuery());
        e.setTitle(d.getTitle());
        e.setPersona(d.getPersona());
        e.setStatus(d.getStatus());
        e.setLastSavedAt(d.getLastSavedAt());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningTheme toThemeDomain(DemoLearningThemeEntity e) {
        return DemoLearningTheme.builder()
                .id(e.getId())
                .clerkUserId(e.getClerkUserId())
                .initialQuery(e.getInitialQuery())
                .title(e.getTitle())
                .persona(e.getPersona())
                .status(e.getStatus())
                .lastSavedAt(e.getLastSavedAt())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DemoLearningNodeEntity toNodeEntity(DemoLearningNode d) {
        DemoLearningNodeEntity e = new DemoLearningNodeEntity();
        e.setId(d.getId());
        e.setThemeId(d.getThemeId());
        e.setParentId(d.getParentId());
        e.setNodeType(d.getNodeType());
        e.setTitle(d.getTitle());
        e.setSummary(d.getSummary());
        e.setMasteryLevel(d.getMasteryLevel());
        e.setLearningType(d.getLearningType());
        e.setCertaintyStatus(d.getCertaintyStatus());
        e.setStartMsgId(d.getStartMsgId());
        e.setTrajectory(d.getTrajectory());
        e.setPreTestResults(d.getPreTestResults());
        e.setMetaJson(d.getMetaJson());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningNode toNodeDomain(DemoLearningNodeEntity e) {
        return DemoLearningNode.builder()
                .id(e.getId())
                .themeId(e.getThemeId())
                .parentId(e.getParentId())
                .nodeType(e.getNodeType())
                .title(e.getTitle())
                .summary(e.getSummary())
                .masteryLevel(e.getMasteryLevel())
                .learningType(e.getLearningType())
                .certaintyStatus(e.getCertaintyStatus())
                .startMsgId(e.getStartMsgId())
                .trajectory(e.getTrajectory())
                .preTestResults(e.getPreTestResults())
                .metaJson(e.getMetaJson())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DemoLearningEdgeEntity toEdgeEntity(DemoLearningEdge d) {
        DemoLearningEdgeEntity e = new DemoLearningEdgeEntity();
        e.setId(d.getId());
        e.setThemeId(d.getThemeId());
        e.setSourceId(d.getSourceId());
        e.setTargetId(d.getTargetId());
        e.setLabel(d.getLabel());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningEdge toEdgeDomain(DemoLearningEdgeEntity e) {
        return DemoLearningEdge.builder()
                .id(e.getId())
                .themeId(e.getThemeId())
                .sourceId(e.getSourceId())
                .targetId(e.getTargetId())
                .label(e.getLabel())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DemoLearningMessageEntity toMessageEntity(DemoLearningMessage d) {
        DemoLearningMessageEntity e = new DemoLearningMessageEntity();
        e.setId(d.getId());
        e.setThemeId(d.getThemeId());
        e.setRole(d.getRole());
        e.setContent(d.getContent());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningMessage toMessageDomain(DemoLearningMessageEntity e) {
        return DemoLearningMessage.builder()
                .id(e.getId())
                .themeId(e.getThemeId())
                .role(e.getRole())
                .content(e.getContent())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DemoLearningAgentStateEntity toAgentStateEntity(DemoLearningAgentState d) {
        DemoLearningAgentStateEntity e = new DemoLearningAgentStateEntity();
        e.setThemeId(d.getThemeId());
        e.setCurrentFocusNodeId(d.getCurrentFocusNodeId());
        e.setPendingOutline(d.getPendingOutline());
        e.setCurrentLearningStage(d.getCurrentLearningStage());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningAgentState toAgentStateDomain(DemoLearningAgentStateEntity e) {
        return DemoLearningAgentState.builder()
                .themeId(e.getThemeId())
                .currentFocusNodeId(e.getCurrentFocusNodeId())
                .pendingOutline(e.getPendingOutline())
                .currentLearningStage(e.getCurrentLearningStage())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private DemoLearningUserProfileEntity toUserProfileEntity(DemoLearningUserProfile d) {
        DemoLearningUserProfileEntity e = new DemoLearningUserProfileEntity();
        e.setId(d.getId());
        e.setClerkUserId(d.getClerkUserId());
        e.setPreferences(d.getPreferences());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private DemoLearningUserProfile toUserProfileDomain(DemoLearningUserProfileEntity e) {
        return DemoLearningUserProfile.builder()
                .id(e.getId())
                .clerkUserId(e.getClerkUserId())
                .preferences(e.getPreferences())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
