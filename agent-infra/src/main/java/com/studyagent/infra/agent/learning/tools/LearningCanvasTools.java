package com.studyagent.infra.agent.learning.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.DemoLearningUserProfile;
import com.studyagent.service.domain.demo.learning.repo.DemoLearningCanvasRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learning Canvas 工具集 —— demo（server/src/tools.ts）的 Java 移植。
 * <p>
 * 每个工具实现 {@link ToolCallback}（Spring AI 1.x）：getToolDefinition 提供
 * name/description/inputSchema（模型可见），call(json) 执行并返回 JSON 字符串。
 * themeId 经 {@link #withTheme(Long)} 线程上下文传入，避免把内部参数暴露给模型。
 * <p>
 * 18 个工具：init_learning_canvas / create_sub_nodes / update_focus / update_node /
 * trigger_pre_test / trigger_post_test / trigger_comprehensive_quiz / apply_quiz_outcome /
 * trigger_feynman_slice / build_analogy / record_aha_moment / connect_nodes / delete_node /
 * update_user_profile / generate_visual_anchor / generate_cognitive_map / fetch_authoritative / search_web。
 */
@Component
public class LearningCanvasTools {

    private static final Logger log = LoggerFactory.getLogger(LearningCanvasTools.class);

    private final DemoLearningCanvasRepository repo;
    private final ObjectMapper objectMapper;

    /** 当前回合 themeId（运行时经 withTheme 注入，与 PromptFactory 的 ThreadLocal 一致） */
    private static final ThreadLocal<Long> THEME = new ThreadLocal<>();

    public LearningCanvasTools(DemoLearningCanvasRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public static void withTheme(Long themeId) {
        THEME.set(themeId);
    }

    public static void clearTheme() {
        THEME.remove();
    }

    public Long themeId() {
        return THEME.get();
    }

    /** 全部工具回调（画布就绪后开放） */
    public List<ToolCallback> allCallbacks() {
        return List.of(
                initLearningCanvas(), createSubNodes(), updateFocus(), updateNode(),
                triggerPreTest(), triggerPostTest(), triggerComprehensiveQuiz(), applyQuizOutcome(),
                triggerFeynmanSlice(), buildAnalogy(), recordAhaMoment(), connectNodes(), deleteNode(),
                updateUserProfile(), generateVisualAnchor(), generateCognitiveMap(), fetchAuthoritative(), searchWeb());
    }

    public List<String> allToolNames() {
        return allCallbacks().stream().map(c -> c.getToolDefinition().name()).toList();
    }

    // =================================================================
    // 工具定义（每个工具一个 ToolCallback）
    // =================================================================

    public ToolCallback initLearningCanvas() {
        return tool("init_learning_canvas",
                "轻诊断信息足够后调用，一次性建立第一版学习资产图：（1）命名主题 Theme，（2）拆分 3-7 个核心主线/资产雏形节点。这是从诊断阶段切换到学习阶段的唯一入口。调用前后应输出 LearningBrief：Object、Goal、Starting Point、Path、Asset。",
                """
                {"type":"object","properties":{
                  "themeTitle":{"type":"string","description":"提取出的学习主题（用户级别的宏观命名），例如：'线性代数基础'"},
                  "outline":{"type":"array","description":"3-7 个核心节点，按学习顺序排列",
                    "items":{"type":"object","properties":{
                      "title":{"type":"string"},
                      "summary":{"type":"string","description":"一句话概括该节点为什么在本次学习资产图里重要"},
                      "learningType":{"type":"string","enum":["theory","practice","mixed"]},
                      "certaintyStatus":{"type":"string","enum":["confirmed","tentative"]}},
                      "required":["title","summary","learningType","certaintyStatus"]}}},
                 "required":["themeTitle","outline"]}
                """,
                args -> {
                    Long themeId = themeId();
                    DemoLearningTheme theme = repo.findThemeById(themeId);
                    if (theme == null) {
                        return err("主题不存在");
                    }
                    theme.setTitle(str(args, "themeTitle", theme.getInitialQuery()));
                    theme.setStatus("in_progress");
                    theme.setLastSavedAt(LocalDateTime.now());
                    repo.saveTheme(theme);

                    List<Long> created = new ArrayList<>();
                    JsonNode outline = args.get("outline");
                    if (outline != null && outline.isArray()) {
                        int index = 0;
                        for (JsonNode item : outline) {
                            if (index >= 7) {
                                break;
                            }
                            DemoLearningNode node = DemoLearningNode.builder()
                                    .themeId(themeId)
                                    .nodeType("knowledge")
                                    .title(str(item, "title", "核心知识 " + (index + 1)))
                                    .summary(str(item, "summary", ""))
                                    .masteryLevel("生疏")
                                    .learningType(str(item, "learningType", "theory"))
                                    .certaintyStatus(str(item, "certaintyStatus", "confirmed"))
                                    .build();
                            node = repo.saveNode(node);
                            created.add(node.getId());
                            index++;
                        }
                    }
                    DemoLearningAgentState state = repo.getAgentState(themeId);
                    if (state == null) {
                        state = DemoLearningAgentState.builder().themeId(themeId)
                                .currentFocusNodeId(created.isEmpty() ? null : created.get(0))
                                .pendingOutline(toJson(created))
                                .currentLearningStage("map_orientation")
                                .updatedAt(LocalDateTime.now()).build();
                    } else {
                        state.setCurrentFocusNodeId(created.isEmpty() ? state.getCurrentFocusNodeId() : created.get(0));
                        state.setPendingOutline(toJson(created));
                        state.setCurrentLearningStage("map_orientation");
                        state.setUpdatedAt(LocalDateTime.now());
                    }
                    repo.saveAgentState(state);
                    return ok(Map.of("themeId", themeId, "nodeIds", created, "message", "第一版学习资产图已建立"));
                });
    }

    public ToolCallback createSubNodes() {
        return tool("create_sub_nodes",
                "在画布上补充创建【多个子节点】。当你需要对当前母节点做更细粒度拆分，或者用户提问引发新的学习分支时调用。通常一次性拆分出 2-4 个具体子节点。",
                """
                {"type":"object","properties":{
                  "parentId":{"type":"number","description":"母节点 ID（画布上的真实节点 ID，不要传标题）"},
                  "nodes":{"type":"array","description":"要拆分出的多个子节点列表",
                    "items":{"type":"object","properties":{
                      "title":{"type":"string"},
                      "summary":{"type":"string"},
                      "learningType":{"type":"string","enum":["theory","practice","mixed"]},
                      "certaintyStatus":{"type":"string","enum":["confirmed","tentative"]}},
                      "required":["title","summary","learningType","certaintyStatus"]}}},
                 "required":["nodes"]}
                """,
                args -> {
                    Long themeId = themeId();
                    JsonNode nodes = args.get("nodes");
                    if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                        return err("nodes 不能为空");
                    }
                    Long parentId = args.has("parentId") && !args.get("parentId").isNull()
                            ? args.get("parentId").asLong() : focusNodeId(themeId);
                    if (parentId == null) {
                        return err("缺少 parentId 且当前无焦点节点");
                    }
                    List<Long> created = new ArrayList<>();
                    for (JsonNode item : nodes) {
                        DemoLearningNode node = DemoLearningNode.builder()
                                .themeId(themeId).parentId(parentId).nodeType("knowledge")
                                .title(str(item, "title", "子知识")).summary(str(item, "summary", ""))
                                .masteryLevel("生疏").learningType(str(item, "learningType", "theory"))
                                .certaintyStatus(str(item, "certaintyStatus", "tentative"))
                                .build();
                        node = repo.saveNode(node);
                        created.add(node.getId());
                    }
                    return ok(Map.of("parentId", parentId, "nodeIds", created, "message", "已生成 " + created.size() + " 个待定子节点"));
                });
    }

    public ToolCallback updateFocus() {
        return tool("update_focus",
                "切换当前焦点节点。每当叙述焦点转移到某个节点就调用，让用户视线跟着讲解走。",
                "{\"type\":\"object\",\"properties\":{\"nodeId\":{\"type\":\"number\",\"description\":\"目标节点 ID\"}},\"required\":[\"nodeId\"]}",
                args -> {
                    Long themeId = themeId();
                    Long nodeId = num(args, "nodeId");
                    if (nodeId == null) {
                        return err("缺少 nodeId");
                    }
                    DemoLearningNode node = repo.findNodeById(nodeId);
                    if (node == null || !node.getThemeId().equals(themeId)) {
                        return err("节点不存在或不属于当前主题");
                    }
                    DemoLearningAgentState state = stateOrCreate(themeId);
                    state.setCurrentFocusNodeId(nodeId);
                    state.setUpdatedAt(LocalDateTime.now());
                    repo.saveAgentState(state);
                    return ok(Map.of("nodeId", nodeId, "title", node.getTitle()));
                });
    }

    public ToolCallback updateNode() {
        return tool("update_node",
                "更新节点：milestone 留痕（记录用户认知经历）/ mastery 升级 / summary / certainty。注意：测验最多升到'理解'，'熟练'必须用户费曼复述通过后由你判断。",
                """
                {"type":"object","properties":{
                  "nodeId":{"type":"number"},
                  "summary":{"type":"string","description":"用户的理解切片/大白话总结"},
                  "mastery":{"type":"string","enum":["生疏","理解","熟练"]},
                  "certaintyStatus":{"type":"string","enum":["confirmed","tentative"]},
                  "milestone":{"type":"object","description":"留痕：记录用户认知经历",
                    "properties":{"type":{"type":"string","enum":["summary","info","insight"]},
                                  "content":{"type":"string"}}}},
                 "required":["nodeId"]}
                """,
                args -> {
                    Long themeId = themeId();
                    Long nodeId = num(args, "nodeId");
                    if (nodeId == null) {
                        return err("缺少 nodeId");
                    }
                    DemoLearningNode node = repo.findNodeById(nodeId);
                    if (node == null || !node.getThemeId().equals(themeId)) {
                        return err("节点不存在或不属于当前主题");
                    }
                    if (args.has("summary")) {
                        node.setSummary(args.get("summary").asText());
                    }
                    if (args.has("certaintyStatus")) {
                        node.setCertaintyStatus(args.get("certaintyStatus").asText());
                    }
                    if (args.has("mastery")) {
                        String mastery = args.get("mastery").asText();
                        if ("熟练".equals(mastery)) {
                            DemoLearningAgentState st = repo.getAgentState(themeId);
                            boolean inCrystallization = st != null && "crystallization".equals(st.getCurrentLearningStage());
                            if (!inCrystallization) {
                                mastery = "理解";
                                log.info("[LearningCanvas] update_node mastery=熟练 被降级为 理解（非费曼阶段）: nodeId={}", nodeId);
                            }
                        }
                        node.setMasteryLevel(mastery);
                    }
                    if (args.has("milestone")) {
                        JsonNode ms = args.get("milestone");
                        String content = ms.has("content") ? ms.get("content").asText() : "";
                        String type = ms.has("type") ? ms.get("type").asText() : "info";
                        String trajectory = node.getTrajectory() == null ? "[]" : node.getTrajectory();
                        try {
                            var list = objectMapper.readValue(trajectory, List.class);
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("type", type);
                            entry.put("content", content);
                            entry.put("at", LocalDateTime.now().toString());
                            list.add(entry);
                            node.setTrajectory(objectMapper.writeValueAsString(list));
                        } catch (Exception ex) {
                            log.warn("[LearningCanvas] trajectory append failed: {}", ex.getMessage());
                        }
                    }
                    node.setUpdatedAt(LocalDateTime.now());
                    repo.saveNode(node);
                    return ok(Map.of("nodeId", nodeId, "title", node.getTitle(), "masteryLevel", node.getMasteryLevel()));
                });
    }

    public ToolCallback triggerPreTest() {
        return tool("trigger_pre_test",
                "节点摸底（pre-test）：制造认知张力。题目由系统渲染成组件，你只需 1-2 句引导，不要自己输出题目。每道题必须有'不确定/没把握'选项。",
                """
                {"type":"object","properties":{
                  "nodeId":{"type":"number","description":"要摸底的知识节点 ID"},
                  "questions":{"type":"array","description":"3-6 道摸底题",
                    "items":{"type":"object","properties":{
                      "question":{"type":"string"},
                      "options":{"type":"array","items":{"type":"string"},"description":"含'不确定/没把握'选项"},
                      "correctIndex":{"type":"number"},
                      "explanation":{"type":"string"}},
                      "required":["question","options"]}}},
                 "required":["nodeId","questions"]}
                """,
                args -> {
                    Long themeId = themeId();
                    Long nodeId = args.has("nodeId") ? args.get("nodeId").asLong() : focusNodeId(themeId);
                    if (nodeId == null) {
                        return err("缺少 nodeId 且当前无焦点节点");
                    }
                    DemoLearningNode node = repo.findNodeById(nodeId);
                    if (node == null) {
                        return err("节点不存在");
                    }
                    if (args.has("questions")) {
                        node.setPreTestResults(args.get("questions").toString());
                        node.setUpdatedAt(LocalDateTime.now());
                        repo.saveNode(node);
                    }
                    setStage(themeId, "pre_test");
                    return ok(Map.of("nodeId", nodeId, "nodeTitle", node.getTitle(), "mode", "pre"));
                });
    }

    public ToolCallback triggerPostTest() {
        return tool("trigger_post_test",
                "节点后测（post-test）：让用户重做同一组题。若该节点 pre_test 已满分可跳过；进步明显才做 Before/After 对比。",
                "{\"type\":\"object\",\"properties\":{\"nodeId\":{\"type\":\"number\"}},\"required\":[\"nodeId\"]}",
                args -> {
                    Long themeId = themeId();
                    Long nodeId = args.has("nodeId") ? args.get("nodeId").asLong() : focusNodeId(themeId);
                    if (nodeId == null) {
                        return err("缺少 nodeId 且当前无焦点节点");
                    }
                    DemoLearningNode node = repo.findNodeById(nodeId);
                    if (node == null) {
                        return err("节点不存在");
                    }
                    if (node.getPreTestResults() == null) {
                        return err("该节点没有 pre-test 记录，无法后测");
                    }
                    setStage(themeId, "post_test");
                    return ok(Map.of("nodeId", nodeId, "nodeTitle", node.getTitle(), "mode", "post", "questions", node.getPreTestResults()));
                });
    }

    public ToolCallback triggerComprehensiveQuiz() {
        return tool("trigger_comprehensive_quiz",
                "综合摸底（自主模式）：一次性出 8-15 题全面摸底，覆盖整张知识地图，用来查漏补缺。触发前必须先让用户知道这是全面摸底阶段。",
                """
                {"type":"object","properties":{
                  "questions":{"type":"array","description":"8-15 道题",
                    "items":{"type":"object","properties":{
                      "question":{"type":"string"},
                      "options":{"type":"array","items":{"type":"string"},"description":"含'不确定/没把握'选项"},
                      "correctIndex":{"type":"number"},
                      "explanation":{"type":"string"}},
                      "required":["question","options"]}}},
                 "required":["questions"]}
                """,
                args -> {
                    setStage(themeId(), "comprehensive_quiz");
                    return ok(Map.of("mode", "pre", "variant", "comprehensive"));
                });
    }

    public ToolCallback applyQuizOutcome() {
        return tool("apply_quiz_outcome",
                "综合摸底结果批量染色：答对的知识点最多设为'理解'，答错或不确定的留'生疏'（盲区）。测验不能自动判为熟练。",
                """
                {"type":"object","properties":{
                  "results":{"type":"array","description":"按题目的节点映射结果",
                    "items":{"type":"object","properties":{
                      "nodeId":{"type":"number"},
                      "correct":{"type":"boolean"}},
                      "required":["nodeId","correct"]}}},
                 "required":["results"]}
                """,
                args -> {
                    Long themeId = themeId();
                    JsonNode results = args.get("results");
                    if (results == null || !results.isArray()) {
                        return err("results 不能为空");
                    }
                    List<Map<String, Object>> colored = new ArrayList<>();
                    for (JsonNode r : results) {
                        Long nodeId = r.has("nodeId") ? r.get("nodeId").asLong() : null;
                        if (nodeId == null) {
                            continue;
                        }
                        DemoLearningNode node = repo.findNodeById(nodeId);
                        if (node == null || !node.getThemeId().equals(themeId)) {
                            continue;
                        }
                        boolean correct = r.has("correct") && r.get("correct").asBoolean(false);
                        node.setMasteryLevel(correct && !"生疏".equals(node.getMasteryLevel()) ? "理解" : "生疏");
                        node.setUpdatedAt(LocalDateTime.now());
                        repo.saveNode(node);
                        colored.add(Map.of("nodeId", nodeId, "masteryLevel", node.getMasteryLevel()));
                    }
                    return ok(Map.of("colored", colored, "message", "已按摸底结果染色 " + colored.size() + " 个节点"));
                });
    }

    public ToolCallback triggerFeynmanSlice() {
        return tool("trigger_feynman_slice",
                "费曼复述（crystallization）：让用户用自己的话解释概念，作为'理解切片'固化到画布。这是唯一可自动升'熟练'的路径。",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\",\"description\":\"让用户复述的概念/主题\"}},\"required\":[\"topic\"]}",
                args -> {
                    setStage(themeId(), "crystallization");
                    return ok(Map.of("topic", str(args, "topic", "")));
                });
    }

    public ToolCallback buildAnalogy() {
        return tool("build_analogy",
                "为抽象概念构建类比：挂 evidence 子节点到画布。类比必须是用户能形成脑内画面的生活场景。",
                """
                {"type":"object","properties":{
                  "parentNodeId":{"type":"number"},
                  "concept":{"type":"string"},
                  "analogy":{"type":"string","description":"类比对象（生活场景）"},
                  "explanation":{"type":"string"}},
                 "required":["parentNodeId","concept","analogy"]}
                """,
                args -> {
                    Long themeId = themeId();
                    Long parentId = args.has("parentNodeId") ? args.get("parentNodeId").asLong() : focusNodeId(themeId);
                    if (parentId == null) {
                        return err("缺少 parentNodeId 且当前无焦点节点");
                    }
                    String concept = str(args, "concept", "");
                    String analogy = str(args, "analogy", "");
                    String explanation = str(args, "explanation", "");
                    DemoLearningNode node = DemoLearningNode.builder()
                            .themeId(themeId).parentId(parentId).nodeType("evidence")
                            .title("类比: " + concept).summary(analogy)
                            .masteryLevel("生疏").learningType("theory").certaintyStatus("confirmed")
                            .build();
                    repo.saveNode(node);
                    return ok(Map.of("message", "类比构建成功，已自动在画布上挂载类比节点。请直接向用户输出：【" + concept + " 就好比 " + analogy + "】：" + explanation));
                });
    }

    public ToolCallback recordAhaMoment() {
        return tool("record_aha_moment",
                "记录用户顿悟（quote 必须来自用户原话或对原话的极轻微压缩，严禁模型自创）。只在用户真的说出'我懂了/原来是这样'类表达时调用。",
                """
                {"type":"object","properties":{
                  "parentNodeId":{"type":"number"},
                  "quote":{"type":"string","description":"用户原话顿悟"}},
                 "required":["parentNodeId","quote"]}
                """,
                args -> {
                    Long themeId = themeId();
                    Long parentId = args.has("parentNodeId") ? args.get("parentNodeId").asLong() : focusNodeId(themeId);
                    if (parentId == null) {
                        return err("缺少 parentNodeId 且当前无焦点节点");
                    }
                    String quote = str(args, "quote", "");
                    DemoLearningNode node = DemoLearningNode.builder()
                            .themeId(themeId).parentId(parentId).nodeType("dialogue_step")
                            .title("💡 " + (quote.length() > 24 ? quote.substring(0, 24) + "…" : quote))
                            .summary(quote).masteryLevel("理解").learningType("theory").certaintyStatus("confirmed")
                            .build();
                    repo.saveNode(node);
                    return ok(Map.of("message", "已记录用户顿悟切片"));
                });
    }

    public ToolCallback connectNodes() {
        return tool("connect_nodes",
                "跨节点连线：表达两个知识点之间的关系（前置/推导/对比等）。",
                """
                {"type":"object","properties":{
                  "sourceId":{"type":"number"},
                  "targetId":{"type":"number"},
                  "label":{"type":"string","description":"关系标签，如 前置/包含/对比"}},
                 "required":["sourceId","targetId"]}
                """,
                args -> {
                    Long sourceId = num(args, "sourceId");
                    Long targetId = num(args, "targetId");
                    if (sourceId == null || targetId == null) {
                        return err("缺少 sourceId / targetId");
                    }
                    var edge = com.studyagent.service.domain.demo.learning.DemoLearningEdge.builder()
                            .themeId(themeId()).sourceId(sourceId).targetId(targetId)
                            .label(str(args, "label", "")).build();
                    repo.saveEdge(edge);
                    return ok(Map.of("sourceId", sourceId, "targetId", targetId));
                });
    }

    public ToolCallback deleteNode() {
        return tool("delete_node",
                "删除节点（如用户已掌握的待定子节点，或错误节点）。",
                "{\"type\":\"object\",\"properties\":{\"nodeId\":{\"type\":\"number\"}},\"required\":[\"nodeId\"]}",
                args -> {
                    Long themeId = themeId();
                    Long nodeId = num(args, "nodeId");
                    if (nodeId == null) {
                        return err("缺少 nodeId");
                    }
                    DemoLearningNode node = repo.findNodeById(nodeId);
                    if (node == null || !node.getThemeId().equals(themeId)) {
                        return err("节点不存在或不属于当前主题");
                    }
                    repo.deleteNode(nodeId);
                    return ok(Map.of("nodeId", nodeId, "message", "节点已删除"));
                });
    }

    public ToolCallback updateUserProfile() {
        return tool("update_user_profile",
                "更新跨主题用户偏好（L0 Memory）：发现持续偏好（写作/数学/看代码等）时调用。",
                "{\"type\":\"object\",\"properties\":{\"preference\":{\"type\":\"string\"}},\"required\":[\"preference\"]}",
                args -> {
                    Long themeId = themeId();
                    DemoLearningTheme theme = repo.findThemeById(themeId);
                    if (theme == null) {
                        return err("主题不存在");
                    }
                    String clerkUserId = theme.getClerkUserId();
                    DemoLearningUserProfile profile = repo.getUserProfile(clerkUserId);
                    List<String> prefs = new ArrayList<>();
                    if (profile != null && profile.getPreferences() != null && !profile.getPreferences().isBlank()) {
                        try {
                            for (JsonNode n : objectMapper.readTree(profile.getPreferences())) {
                                prefs.add(n.asText());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    String p = str(args, "preference", "");
                    if (!p.isBlank() && !prefs.contains(p)) {
                        prefs.add(p);
                    }
                    if (profile == null) {
                        profile = DemoLearningUserProfile.builder().clerkUserId(clerkUserId).preferences(toJson(prefs)).build();
                    } else {
                        profile.setPreferences(toJson(prefs));
                    }
                    repo.saveUserProfile(profile);
                    return ok(Map.of("preferences", prefs));
                });
    }

    // ---- 本期占位工具（返回 not_supported） ----

    public ToolCallback generateVisualAnchor() {
        return tool("generate_visual_anchor",
                "生成视觉记忆锚点图（抽象概念/类比/误区用）。本期未接入生图服务。",
                "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"]}",
                args -> err("生图能力本期未接入（FAL_KEY 未配置）"));
    }

    public ToolCallback generateCognitiveMap() {
        return tool("generate_cognitive_map",
                "母节点全部点亮后生成认知网络图。本期未接入生图服务。",
                "{\"type\":\"object\",\"properties\":{\"parentNodeId\":{\"type\":\"number\"}},\"required\":[\"parentNodeId\"]}",
                args -> err("认知图生成本期未接入"));
    }

    public ToolCallback fetchAuthoritative() {
        return tool("fetch_authoritative",
                "外部权威来源检索（论文/教材/原典）。本期未接入检索服务。",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}",
                args -> err("外部学术检索本期未接入"));
    }

    public ToolCallback searchWeb() {
        return tool("search_web",
                "网页搜索（最新事实/新闻/现实案例）。本期未接入检索服务。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
                args -> err("网页搜索本期未接入"));
    }

    // =================================================================
    // 工具构建辅助
    // =================================================================

    private ToolCallback tool(String name, String description, String inputSchemaJson, ToolExec exec) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchemaJson)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                try {
                    JsonNode args = toolInput == null || toolInput.isBlank()
                            ? objectMapper.createObjectNode() : objectMapper.readTree(toolInput);
                    Object result = exec.apply(args);
                    return result instanceof String s ? s : objectMapper.writeValueAsString(result);
                } catch (Exception ex) {
                    log.warn("[LearningCanvas] tool {} failed: {}", name, ex.getMessage());
                    return err("工具执行出错：" + ex.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface ToolExec {
        Object apply(JsonNode args) throws Exception;
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.putAll(data);
        return toJson(payload);
    }

    private String err(String message) {
        return toJson(Map.of("success", false, "message", message));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private void setStage(Long themeId, String stage) {
        DemoLearningAgentState state = stateOrCreate(themeId);
        state.setCurrentLearningStage(stage);
        state.setUpdatedAt(LocalDateTime.now());
        repo.saveAgentState(state);
    }

    private DemoLearningAgentState stateOrCreate(Long themeId) {
        DemoLearningAgentState state = repo.getAgentState(themeId);
        if (state == null) {
            state = DemoLearningAgentState.builder().themeId(themeId).updatedAt(LocalDateTime.now()).build();
        }
        return state;
    }

    private Long focusNodeId(Long themeId) {
        DemoLearningAgentState state = repo.getAgentState(themeId);
        return state == null ? null : state.getCurrentFocusNodeId();
    }

    private String str(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field) == null || node.get(field).isNull()) {
            return fallback;
        }
        JsonNode v = node.get(field);
        return v.isTextual() ? v.asText() : v.toString();
    }

    private Long num(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asLong();
    }
}
