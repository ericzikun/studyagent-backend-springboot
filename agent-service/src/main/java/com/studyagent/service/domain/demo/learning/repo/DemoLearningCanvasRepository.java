package com.studyagent.service.domain.demo.learning.repo;

import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningEdge;
import com.studyagent.service.domain.demo.learning.DemoLearningMessage;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.DemoLearningUserProfile;

import java.util.List;

/**
 * Learning Canvas Demo 仓储接口（聚合本产品全部新表访问）
 * <p>
 * 只新增本 Demo 数据；不触碰任何旧仓储/旧表。实现见 agent-infra
 * {@code com.studyagent.infra.repository.demo.learning.DemoLearningCanvasRepositoryImpl}。
 */
public interface DemoLearningCanvasRepository {

    // ---------- theme ----------
    DemoLearningTheme saveTheme(DemoLearningTheme theme);

    DemoLearningTheme findThemeById(Long id);

    DemoLearningTheme findThemeByIdAndUser(Long id, String clerkUserId);

    List<DemoLearningTheme> listThemesByUser(String clerkUserId, int limit);

    void touchThemeSavedAt(Long id);

    // ---------- node ----------
    DemoLearningNode saveNode(DemoLearningNode node);

    DemoLearningNode findNodeById(Long id);

    List<DemoLearningNode> listNodesByTheme(Long themeId);

    List<DemoLearningNode> listKnowledgeNodesByTheme(Long themeId);

    boolean deleteNode(Long id);

    // ---------- edge ----------
    DemoLearningEdge saveEdge(DemoLearningEdge edge);

    List<DemoLearningEdge> listEdgesByTheme(Long themeId);

    // ---------- message ----------
    DemoLearningMessage saveMessage(DemoLearningMessage message);

    List<DemoLearningMessage> listMessagesByTheme(Long themeId);

    void appendToLastAssistantMessage(Long themeId, String appendContent);

    // ---------- agent state ----------
    DemoLearningAgentState getAgentState(Long themeId);

    void saveAgentState(DemoLearningAgentState state);

    // ---------- user profile ----------
    DemoLearningUserProfile getUserProfile(String clerkUserId);

    void saveUserProfile(DemoLearningUserProfile profile);
}
