package com.studyagent.infra.agent.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningMessage;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.repo.DemoLearningCanvasRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learning Canvas Agent 运行时 —— demo（server/src/agent.ts）的 Java 移植。
 * <p>
 * 核心机制：
 * <ul>
 *   <li>单 Agent + function calling + 递归接力（depth ≤ 8），工具调用后默认续写；</li>
 *   <li>STOP_AFTER_TOOLS：测验类工具调用后必须停下等用户答题；</li>
 *   <li>动态系统提示：阶段硬规则（stageHardGuard）+ L0/L1/L2 三档内存；</li>
 *   <li>动态工具策略：按画布/诊断状态决定给模型哪些工具、是否强制单工具；</li>
 *   <li>组件注入：dual_test / feynman 由后端生成 markdown，不依赖模型输出；</li>
 *   <li>文本组件块自动挂画布：正则扫描 quiz/survey/compare/animation 等代码块 → 建资产节点。</li>
 * </ul>
 * 只新增本 Demo 代码；LLM 调用走 Spring AI（ChatModel + @Tool），不直连 vendor。
 */
@Component
public class LearningCanvasAgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(LearningCanvasAgentRuntime.class);

    /** 工具调用后递归接力的最大深度（demo MAX_AGENT_DEPTH=8） */
    static final int MAX_AGENT_DEPTH = 8;

    /** 测验触发型工具：调用后必须把控制权交回用户（demo STOP_AFTER_TOOLS） */
    static final Set<String> STOP_AFTER_TOOLS = Set.of(
            "trigger_pre_test", "trigger_post_test", "trigger_feynman_slice", "trigger_comprehensive_quiz");

    private static final Pattern NEXT_ACTION_BLOCK = Pattern.compile("```next-action\\s*([\\s\\S]*?)```");
    private static final Pattern COMPONENT_BLOCK =
            Pattern.compile("```(quiz|survey|compare|simulation|animation|sandbox|flashcard|feynman|dual_test|ranking|fill_blank|multi_choice|matching|categorize)\\n?([\\s\\S]*?)```");

    private static final Set<String> INTERNAL_USER_PREFIX = Set.of(
            "【组件提交】", "【节点侧边栏深聊】", "【Knowledge Map 入口已选择】", "【节点对话模式】", "【系统内部】");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final LearningCanvasModelProperties modelProperties;
    private final DemoLearningCanvasRepository repo;
    private final LearningCanvasPromptFactory promptFactory;
    private final LearningCanvasToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public LearningCanvasAgentRuntime(
            ObjectProvider<ChatModel> chatModelProvider,
            LearningCanvasModelProperties modelProperties,
            DemoLearningCanvasRepository repo,
            LearningCanvasPromptFactory promptFactory,
            LearningCanvasToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.modelProperties = modelProperties;
        this.repo = repo;
        this.promptFactory = promptFactory;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    // =================================================================
    // 对外入口：处理一个用户回合
    // =================================================================

    /**
     * 处理用户一条消息。事件通过 {@code emit} 回调逐条下发（SSE 层透传）。
     *
     * @param themeId      主题 id
     * @param userMessage  用户消息（可能为内部指令，如组件提交/节点深聊）
     * @param saveUserMessage 是否持久化该用户消息
     * @param emit         事件回调
     */
    public void run(Long themeId, String userMessage, boolean saveUserMessage, Consumer<LearningCanvasStreamEvent> emit) {
        if (chatModelProvider.getIfAvailable() == null) {
            throw new IllegalStateException("No Spring AI ChatModel bean found. Please configure spring.ai.openai.api-key / base-url / model.");
        }
        modelProperties.logResolved();

        LearningCanvasPromptFactory.bindTheme(themeId);
        com.studyagent.infra.agent.learning.tools.LearningCanvasTools.withTheme(themeId);
        try {
            runInternal(themeId, userMessage, saveUserMessage, emit);
        } finally {
            LearningCanvasPromptFactory.clearTheme();
            com.studyagent.infra.agent.learning.tools.LearningCanvasTools.clearTheme();
        }
    }

    private void runInternal(Long themeId, String userMessage, boolean saveUserMessage, Consumer<LearningCanvasStreamEvent> emit) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();

        if (saveUserMessage) {
            repo.saveMessage(message(themeId, "user", userMessage));
        }

        List<Message> history = buildHistory(themeId, userMessage, saveUserMessage);
        String accumulatedText = "";
        for (int depth = 0; depth < MAX_AGENT_DEPTH; depth++) {
            // 1. 工具策略（阶段决定）
            ToolPolicy policy = buildToolPolicy(themeId, history);

            // 2. 组装 prompt 并调用模型
            Prompt prompt = buildPrompt(history, policy, accumulatedText);
            ChatResponse response = chatModel.call(prompt);
            AssistantMessage assistant = extractAssistantMessage(response);

            String text = assistantText(assistant);
            if (!text.isBlank()) {
                accumulatedText = accumulatedText + text;
                emit.accept(LearningCanvasStreamEvent.chunk(text));
            }

            // 3. 工具调用处理（支持一轮多个 tool_calls，每个都要执行并回 tool 响应）
            List<AssistantMessage.ToolCall> toolCalls = assistant.hasToolCalls()
                    ? assistant.getToolCalls() : java.util.List.of();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 无工具调用：保存 assistant 消息 → 组件块挂画布 → 结束
                repo.saveMessage(message(themeId, "assistant", accumulatedText));
                mountAssetsFromText(themeId, accumulatedText, emit);
                return;
            }

            // 4. 持久化 assistant(tool_calls)（含全部 calls）
            repo.saveMessage(toolCallMessage(themeId, accumulatedText, toolCalls));

            boolean anyStopTool = false;
            String firstToolName = toolCalls.get(0).name();
            Map<String, Object> firstToolResult = Map.of("success", false, "message", "no result");
            List<DemoLearningMessage> toolResultMessages = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

            for (AssistantMessage.ToolCall call : toolCalls) {
                String toolName = call.name();
                String toolArgs = call.arguments();
                log.info("[LearningCanvas] tool call: {} args={}", toolName, truncate(toolArgs, 300));
                emit.accept(LearningCanvasStreamEvent.toolStart(toolName));

                Object toolResult;
                try {
                    String resultJson = policy.toolCallbacks.stream()
                            .filter(c -> toolName.equals(c.getToolDefinition().name()))
                            .findFirst()
                            .map(c -> c.call(toolArgs))
                            .orElse("{\"success\":false,\"message\":\"未知工具: " + toolName + "\"}");
                    toolResult = parseResultJson(resultJson);
                } catch (Exception ex) {
                    log.warn("[LearningCanvas] tool exec error: {} - {}", toolName, ex.getMessage());
                    toolResult = Map.of("success", false, "message", "工具执行出错：" + ex.getMessage());
                }

                toolResultMessages.add(toolResultMessage(themeId, call.id(), toolResult));
                toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), toolName, toJsonString(toolResult)));
                emit.accept(LearningCanvasStreamEvent.toolEnd(toolName, toolResult));

                if (call == toolCalls.get(0)) {
                    firstToolResult = asMap(toolResult);
                }
                if (STOP_AFTER_TOOLS.contains(toolName)) {
                    anyStopTool = true;
                }
            }

            // 持久化全部 tool 结果（每个 call 一条，保证上下文可重建）
            for (DemoLearningMessage m : toolResultMessages) {
                repo.saveMessage(m);
            }

            // 5. 组件注入（基于第一个工具的语义；dual_test / feynman 只对测验类工具注入一次）
            AssistantMessage.ToolCall primary = toolCalls.get(0);
            String injected = buildInjectedComponentMarkdown(primary.name(), primary.arguments(), firstToolResult);
            if (injected != null) {
                emit.accept(LearningCanvasStreamEvent.chunk(injected));
                repo.appendToLastAssistantMessage(themeId, injected);
            }

            // 6. STOP 判定 + 画布更新
            emit.accept(LearningCanvasStreamEvent.canvasUpdated());

            if (anyStopTool || depth >= MAX_AGENT_DEPTH - 1) {
                return;
            }

            // 7. 递归接力：注入隐藏 system 指令，继续下一轮
            history.add(assistant);
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());
            history.add(new SystemMessage(buildFollowUpInstruction(firstToolName, themeId)));
        }
    }

    // =================================================================
    // 上下文与 prompt 组装
    // =================================================================

    private List<Message> buildHistory(Long themeId, String userMessage, boolean saveUserMessage) {
        List<Message> messages = new ArrayList<>();
        List<DemoLearningMessage> dbMessages = repo.listMessagesByTheme(themeId);
        for (DemoLearningMessage m : dbMessages) {
            Message converted = convertStoredMessage(m);
            if (converted != null) {
                messages.add(converted);
            }
        }
        if (!saveUserMessage) {
            messages.add(new SystemMessage(userMessage));
        }
        return messages;
    }

    private Message convertStoredMessage(DemoLearningMessage m) {
        try {
            String role = m.getRole();
            String content = m.getContent() == null ? "" : m.getContent();
            if ("user".equals(role)) {
                return new UserMessage(content);
            }
            if ("system".equals(role)) {
                return new SystemMessage(content);
            }
            if ("assistant".equals(role)) {
                JsonNode node = tryParseJson(content);
                if (node != null && node.has("tool_calls") && node.get("tool_calls").isArray()) {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (JsonNode tc : node.get("tool_calls")) {
                        calls.add(new AssistantMessage.ToolCall(
                                tc.path("id").asText(""),
                                "function",
                                tc.path("function").path("name").asText(""),
                                tc.path("function").path("arguments").asText("{}")));
                    }
                    String visible = node.has("content") && node.get("content").isTextual()
                            ? node.get("content").asText() : "";
                    return AssistantMessage.builder()
                            .content(visible)
                            .toolCalls(calls)
                            .build();
                }
                return new AssistantMessage(content);
            }
            if ("tool".equals(role)) {
                JsonNode node = tryParseJson(content);
                if (node != null) {
                    return ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    node.path("tool_call_id").asText(""),
                                    node.path("name").asText(""),
                                    node.path("content").asText("{}"))))
                            .build();
                }
                return ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("", "", content)))
                        .build();
            }
        } catch (Exception ex) {
            log.warn("[LearningCanvas] message conversion failed role={}: {}", m.getRole(), ex.getMessage());
        }
        return null;
    }

    private Prompt buildPrompt(List<Message> history, ToolPolicy policy, String accumulatedText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptFactory.build(history, accumulatedText)));
        messages.addAll(history);

        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder().model(modelProperties.modelName());
        if (isTemperatureSupported()) {
            opts.temperature(modelProperties.temperature());
        }
        if (policy.toolCallbacks != null && !policy.toolCallbacks.isEmpty()) {
            opts.toolCallbacks(policy.toolCallbacks);
            // 手动控制工具执行：模型返回 tool_calls 后由我们执行（工具后继续/停止由产品语义决定）
            opts.internalToolExecutionEnabled(false);
        }
        return new Prompt(messages, opts.build());
    }

    private boolean isTemperatureSupported() {
        String m = modelProperties.modelName().toLowerCase();
        return !(m.contains("gemini") || m.contains("gpt-5") || m.contains("o1") || m.contains("o3"));
    }

    // =================================================================
    // 工具策略（对应 demo buildToolPolicy）
    // =================================================================

    static final class ToolPolicy {
        final List<org.springframework.ai.tool.ToolCallback> toolCallbacks;
        final String reason;

        ToolPolicy(List<org.springframework.ai.tool.ToolCallback> toolCallbacks, String reason) {
            this.toolCallbacks = toolCallbacks;
            this.reason = reason;
        }
    }

    ToolPolicy buildToolPolicy(Long themeId, List<Message> history) {
        List<DemoLearningNode> nodes = repo.listNodesByTheme(themeId);
        int surveyAnswerCount = countSurveyAnswerLogs(history);
        boolean knowledgeMapShown = historyHas(history, "knowledge_map");
        String lastRole = history.isEmpty() ? null : lastRole(history);
        boolean looksAutonomous = conversationLooksAutonomous(history);

        List<DemoLearningNode> knowledgeNodes = nodes.stream()
                .filter(n -> "knowledge".equals(n.getNodeType())).toList();
        List<DemoLearningNode> childKnowledge = knowledgeNodes.stream()
                .filter(n -> n.getParentId() != null).toList();
        List<DemoLearningNode> quizNodes = nodes.stream()
                .filter(n -> "quiz".equals(n.getNodeType())).toList();

        if (nodes.isEmpty()) {
            if (knowledgeMapShown && "user".equals(lastRole)) {
                return new ToolPolicy(toolRegistry.callbacksFor(List.of("init_learning_canvas")),
                        "canvas_empty_after_knowledge_map_entry_force_init");
            }
            return new ToolPolicy(List.of(), "canvas_empty_diagnosis_or_knowledge_map_stage_no_tools_" + surveyAnswerCount);
        }

        if (looksAutonomous && childKnowledge.isEmpty() && quizNodes.isEmpty()) {
            return new ToolPolicy(toolRegistry.callbacksFor(List.of("create_sub_nodes")),
                    "autonomous_after_init_force_sub_nodes");
        }
        if (looksAutonomous && !childKnowledge.isEmpty() && quizNodes.isEmpty()) {
            return new ToolPolicy(toolRegistry.callbacksFor(List.of("trigger_comprehensive_quiz")),
                    "autonomous_after_sub_nodes_force_comprehensive_quiz");
        }

        return new ToolPolicy(toolRegistry.allCallbacks(), "canvas_ready_all_tools");
    }

    private int countSurveyAnswerLogs(List<Message> history) {
        int count = 0;
        for (Message m : history) {
            if (m instanceof SystemMessage sys && sys.getText() != null
                    && sys.getText().contains("用户在诊断问卷")) {
                count++;
            }
        }
        return count;
    }

    private boolean historyHas(List<Message> history, String fragment) {
        for (Message m : history) {
            if (m.getText() != null && m.getText().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String lastRole(List<Message> history) {
        Message last = history.get(history.size() - 1);
        return last.getMessageType() == null ? null : last.getMessageType().getValue();
    }

    private boolean conversationLooksAutonomous(List<Message> history) {
        StringBuilder text = new StringBuilder();
        for (Message m : history) {
            if (m instanceof UserMessage) {
                text.append(m.getText() == null ? "" : m.getText()).append('\n');
            }
        }
        return Pattern.compile("自主模式|快速摸底|查漏补缺|不要太慢|直接测|直接考|直接来题|先测|测我|考我|拆解|借鉴|竞品分析|案例分析")
                .matcher(text.toString()).find();
    }

    // =================================================================
    // 续写指令（对应 demo 的 followUpInstruction）
    // =================================================================

    String buildFollowUpInstruction(String toolName, Long themeId) {
        return switch (toolName) {
            case "init_learning_canvas" -> "[NO_TOOLS_THIS_FOLLOWUP]\n[System] init_learning_canvas 已执行，第一版学习资产图已就绪。本次续写禁止调用任何工具。请只做四件事：1）用 LearningBrief 口吻自然说明 Object、Learning Need、Goal、Starting Point、Path、Asset，重点说明“学习路径是什么”以及“为什么这条路径能达到用户目标”；2）解释右侧画布是资产图雏形，后续会沉淀哪些可带走资产；3）明确说“我先不急着从第一站讲”；4）输出一个 ```survey``` 路线校准选择，选项必须包含“按 Verla 推荐路线走”“先快速摸底查漏补缺”“我自己点右侧节点问”“从我指定的节点开始”。不要 create_sub_nodes，不要 trigger_pre_test，不要 trigger_comprehensive_quiz，不要直接开讲第一个节点。";
            case "create_sub_nodes" -> "[NO_TOOLS_THIS_FOLLOWUP]\n[System] create_sub_nodes 已执行，右侧画布已经长出一批待定子节点。本次续写禁止调用任何工具。请只用 2-4 句话解释这些待定子节点为什么出现、它们如何帮助查漏补缺。最后用 ```next-action``` 收尾，不要在本轮触发 trigger_comprehensive_quiz，也不要继续新增节点。";
            case "update_node" -> "[System] update_node 已执行，画布已经留下焦点/定义/学习轨迹。不要重复刚才的讲解，只做一句自然承接，然后继续当前教学节奏或给出 next-action。";
            case "build_analogy" -> "[System] build_analogy 已执行。接下来只补充这个工具带来的“新增类比/新增解释/新增承接”，不要复述本轮工具调用前已经讲过的内容。";
            case "trigger_comprehensive_quiz" -> "[System] 综合摸底已触发，等待用户答题。不要继续输出内容。";
            default -> "[System] 工具已执行完毕。请继续你的回复——只输出工具带来的新增内容、承接或下一步，不要复述本轮工具调用前已经说过的任何讲解。如果这一轮的回复其实已经完整了，就自然收尾或推进到下一个教学动作。";
        };
    }

    // =================================================================
    // 组件注入（对应 demo 的 injectedComponentMarkdown）
    // =================================================================

    String buildInjectedComponentMarkdown(String toolName, String toolArgs, Object toolResult) {
        try {
            JsonNode args = tryParseJson(toolArgs);
            boolean success = toolResult instanceof Map<?, ?>
                    && Boolean.TRUE.equals(((Map<?, ?>) toolResult).get("success"));
            if (args == null || !success) {
                return null;
            }
            switch (toolName) {
                case "trigger_pre_test": {
                    if (args.has("questions") && args.get("questions").isArray() && args.get("questions").size() > 0) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("mode", "pre");
                        cfg.put("variant", "node_pretest");
                        cfg.put("targetNodeId", resolveEffectiveNodeId(toolArgs, toolResult));
                        cfg.put("questions", args.get("questions"));
                        return "\n\n```dual_test\n" + toJson(cfg) + "\n```\n\n";
                    }
                    return null;
                }
                case "trigger_comprehensive_quiz": {
                    if (args.has("questions") && args.get("questions").isArray() && args.get("questions").size() > 0) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("mode", "pre");
                        cfg.put("variant", "comprehensive");
                        cfg.put("title", "全面摸底");
                        cfg.put("subtitle", "一次性覆盖整张知识地图，用来查漏补缺；答对的节点会被点亮，答错的节点会留下来重点讲。");
                        cfg.put("questions", args.get("questions"));
                        return "\n\n```dual_test\n" + toJson(cfg) + "\n```\n\n";
                    }
                    return null;
                }
                case "trigger_feynman_slice": {
                    String topic = args.path("topic").asText("");
                    if (!topic.isBlank()) {
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("topic", topic);
                        cfg.put("persona", "好奇的小白");
                        cfg.put("starting_question", "你能用一句大白话，把「" + topic + "」讲给一个完全没学过的人听吗？");
                        return "\n\n```feynman\n" + toJson(cfg) + "\n```\n\n";
                    }
                    return null;
                }
                default:
                    return null;
            }
        } catch (Exception ex) {
            log.warn("[LearningCanvas] component injection failed: {}", ex.getMessage());
            return null;
        }
    }

    private Object resolveEffectiveNodeId(String toolArgs, Object toolResult) {
        try {
            if (toolResult instanceof Map<?, ?>) {
                Object nodeId = ((Map<?, ?>) toolResult).get("nodeId");
                if (nodeId != null) {
                    return nodeId;
                }
            }
            JsonNode args = tryParseJson(toolArgs);
            if (args != null && args.has("nodeId")) {
                return args.get("nodeId").asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =================================================================
    // 文本组件块自动挂画布（对应 demo parseBlocks）
    // =================================================================

    void mountAssetsFromText(Long themeId, String text, Consumer<LearningCanvasStreamEvent> emit) {
        DemoLearningAgentState state = repo.getAgentState(themeId);
        if (state == null || state.getCurrentFocusNodeId() == null) {
            return;
        }
        Long focusId = state.getCurrentFocusNodeId();
        Matcher matcher = COMPONENT_BLOCK.matcher(text == null ? "" : text);
        boolean hasNewAssets = false;
        while (matcher.find()) {
            String type = matcher.group(1);
            String body = matcher.group(2);
            String summary = "互动组件";
            try {
                JsonNode cfg = objectMapper.readTree(body);
                if (cfg.has("title")) summary = cfg.get("title").asText();
                else if (cfg.has("question")) summary = cfg.get("question").asText();
                else if (cfg.has("front")) summary = cfg.get("front").asText();
            } catch (Exception ignored) {
            }
            String title = componentTitlePrefix(type) + (summary.length() > 30 ? summary.substring(0, 30) + "..." : summary);
            DemoLearningNode node = DemoLearningNode.builder()
                    .themeId(themeId)
                    .parentId(focusId)
                    .nodeType(type)
                    .title(title)
                    .summary(summary.length() > 200 ? summary.substring(0, 200) : summary)
                    .masteryLevel("生疏")
                    .learningType("theory")
                    .certaintyStatus("confirmed")
                    .build();
            repo.saveNode(node);
            hasNewAssets = true;
        }
        if (hasNewAssets) {
            emit.accept(LearningCanvasStreamEvent.canvasUpdated());
        }
    }

    private String componentTitlePrefix(String type) {
        return switch (type) {
            case "quiz" -> "🏆 阶段小测";
            case "survey" -> "📋 诊断问卷";
            case "compare" -> "⚖️ 概念辨析";
            case "simulation" -> "🎛️ 动态仿真";
            case "animation" -> "▶️ 动画讲解";
            case "sandbox" -> "🧪 交互实验";
            case "flashcard" -> "📇 核心记忆卡";
            case "feynman" -> "🗣️ 翻转课堂";
            case "dual_test" -> "🔄 对比验证";
            case "ranking" -> "🔢 排序题";
            case "fill_blank" -> "✏️ 填空题";
            case "multi_choice" -> "☑️ 多选题";
            case "matching" -> "🔗 配对题";
            case "categorize" -> "🗂️ 分类题";
            default -> "📌 学习资产";
        };
    }

    // =================================================================
    // 工具
    // =================================================================

    // =================================================================
    // 响应解析
    // =================================================================

    private AssistantMessage extractAssistantMessage(ChatResponse response) {
        Generation gen = response.getResult();
        if (gen == null || gen.getOutput() == null) {
            return new AssistantMessage("");
        }
        if (gen.getOutput() instanceof AssistantMessage) {
            return (AssistantMessage) gen.getOutput();
        }
        return new AssistantMessage(gen.getOutput().getText());
    }

    private String assistantText(AssistantMessage assistant) {
        String text = assistant.getText();
        return text == null ? "" : text;
    }

    private DemoLearningMessage message(Long themeId, String role, String content) {
        return DemoLearningMessage.builder()
                .themeId(themeId)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private DemoLearningMessage toolCallMessage(Long themeId, String text, List<AssistantMessage.ToolCall> calls) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", text);
            List<Map<String, Object>> callList = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : calls) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments());
                callList.add(Map.of("id", tc.id(), "type", "function", "function", fn));
            }
            payload.put("tool_calls", callList);
            return message(themeId, "assistant", objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            return message(themeId, "assistant", text);
        }
    }

    private DemoLearningMessage toolResultMessage(Long themeId, String toolCallId, Object result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool_call_id", toolCallId);
            payload.put("content", objectMapper.writeValueAsString(result));
            return message(themeId, "tool", objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            return message(themeId, "tool", String.valueOf(result));
        }
    }

    private String toJsonString(Object result) {
        try {
            if (result instanceof String) {
                return (String) result;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return String.valueOf(result);
        }
    }

    private Map<String, Object> asMap(Object result) {
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of("success", false, "message", "invalid tool result");
    }

    // =================================================================
    // 工具
    // =================================================================

    private JsonNode tryParseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 解析 ToolCallback.call 返回的 JSON 字符串为 Map（供事件透传与组件注入读取）。
     */
    private Object parseResultJson(String json) {
        JsonNode node = tryParseJson(json);
        if (node != null) {
            try {
                return objectMapper.convertValue(node, Map.class);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return Map.of("success", false, "message", json == null ? "empty tool result" : json);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    /**
     * 判断用户消息是否为内部消息（组件提交/节点深聊等），供 Service 层过滤。
     */
    public boolean isInternalUserMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        return INTERNAL_USER_PREFIX.stream().anyMatch(trimmed::startsWith);
    }
}
