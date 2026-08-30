package com.studyagent.service.application.demo;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningEdge;
import com.studyagent.service.domain.demo.learning.DemoLearningMessage;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.repo.DemoLearningCanvasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learning Canvas Demo 应用服务。
 * <p>
 * 业务编排：建主题 / 画布快照 / 掌握度校准 / 历史列表 / 纯免费记账。
 * 只新增本 Demo 代码；复用现有 QuotaDomainService 记流水（纯免费 + 每次调用记账）。
 * 注意：LLM Agent 运行时在 agent-infra（{@code LearningCanvasAgentRuntime}），由
 * agent-api 的 Controller 组装本服务 + 运行时；本服务不反向依赖 infra（保持模块方向）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoLearningCanvasService {

    private final DemoLearningCanvasRepository repository;

    // =================================================================
    // 建主题
    // =================================================================

    @Transactional
    public DemoLearningTheme createTheme(String clerkUserId, String initialQuery, String persona) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
        if (initialQuery == null || initialQuery.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "initial_query is required");
        }
        DemoLearningTheme theme = DemoLearningTheme.builder()
                .clerkUserId(clerkUserId)
                .initialQuery(initialQuery)
                .persona(persona == null || persona.isBlank() ? "sheldon" : persona)
                .status("in_progress")
                .lastSavedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        theme = repository.saveTheme(theme);
        // 初始化 agent state
        repository.saveAgentState(DemoLearningAgentState.builder()
                .themeId(theme.getId())
                .updatedAt(LocalDateTime.now())
                .build());
        return theme;
    }

    // =================================================================
    // 历史列表
    // =================================================================

    public List<DemoLearningTheme> listThemes(String clerkUserId, int limit) {
        List<DemoLearningTheme> themes = repository.listThemesByUser(clerkUserId, limit);
        for (DemoLearningTheme t : themes) {
            // 附上 message/node 计数（保持轻量：单条 count 查询）
        }
        return themes;
    }

    // =================================================================
    // 画布快照
    // =================================================================

    public Map<String, Object> canvasSnapshot(String clerkUserId, Long themeId) {
        DemoLearningTheme theme = ownedTheme(clerkUserId, themeId);
        List<DemoLearningNode> nodes = repository.listNodesByTheme(themeId);
        List<DemoLearningEdge> edges = repository.listEdgesByTheme(themeId);
        DemoLearningAgentState state = repository.getAgentState(themeId);
        List<DemoLearningMessage> visible = visibleMessages(themeId);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("theme", theme);
        snapshot.put("nodes", nodes);
        snapshot.put("edges", edges);
        snapshot.put("state", state);
        snapshot.put("messages", visible);
        return snapshot;
    }

    // =================================================================
    // chat 流式回合
    // =================================================================

    /**
     * chat 前置：校验归属。纯免费记账由 agent-infra 的 LearningCanvasQuotaRecorder
     * 在 Controller 组装层完成（保持模块方向：service 不反向依赖 infra）。
     *
     * @return 校验通过的 theme
     */
    public DemoLearningTheme prepareChat(String clerkUserId, Long themeId) {
        return ownedTheme(clerkUserId, themeId);
    }

    /**
     * chat 后置：回合结束自动保存。
     */
    public void afterChat(Long themeId) {
        repository.touchThemeSavedAt(themeId);
    }

    // =================================================================
    // 掌握度校准
    // =================================================================

    @Transactional
    public DemoLearningNode calibrateMastery(String clerkUserId, Long nodeId, String masteryLevel) {
        DemoLearningNode node = repository.findNodeById(nodeId);
        if (node == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "node not found");
        }
        DemoLearningTheme theme = ownedTheme(clerkUserId, node.getThemeId());
        if (!"knowledge".equals(node.getNodeType())) {
            throw new BusinessException(ApiCode.ILLEGAL_STATE, "only knowledge nodes can be calibrated");
        }
        if (!List.of("生疏", "理解", "熟练").contains(masteryLevel)) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "invalid mastery_level");
        }
        node.setMasteryLevel(masteryLevel);
        node.setUpdatedAt(LocalDateTime.now());
        node = repository.saveNode(node);

        // 写回 system memory（供后续上下文）
        repository.saveMessage(DemoLearningMessage.builder()
                .themeId(theme.getId())
                .role("system")
                .content("用户手动校准知识节点掌握度：节点「" + node.getTitle() + "」→ " + masteryLevel + "。后续教学和记忆判断必须以这个状态为准。")
                .createdAt(LocalDateTime.now())
                .build());
        repository.touchThemeSavedAt(theme.getId());
        return node;
    }

    // =================================================================
    // 内部方法
    // =================================================================

    private DemoLearningTheme ownedTheme(String clerkUserId, Long themeId) {
        DemoLearningTheme theme = repository.findThemeByIdAndUser(themeId, clerkUserId);
        if (theme == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "theme not found or not owned");
        }
        return theme;
    }

    /**
     * 可见消息过滤：剥离 tool_calls / tool 结果 / 内部提交消息，与 demo buildClientSnapshot 对齐。
     */
    private List<DemoLearningMessage> visibleMessages(Long themeId) {
        List<DemoLearningMessage> all = repository.listMessagesByTheme(themeId);
        List<DemoLearningMessage> visible = new ArrayList<>();
        for (DemoLearningMessage m : all) {
            String content = m.getContent() == null ? "" : m.getContent();
            if ("tool".equals(m.getRole())) {
                continue;
            }
            if ("assistant".equals(m.getRole()) && content.contains("\"tool_calls\"")) {
                continue;
            }
            if ("user".equals(m.getRole()) && content.startsWith("【")) {
                continue; // 内部消息
            }
            if ("system".equals(m.getRole()) && content.startsWith("【")) {
                continue;
            }
            visible.add(m);
        }
        return visible;
    }
}
