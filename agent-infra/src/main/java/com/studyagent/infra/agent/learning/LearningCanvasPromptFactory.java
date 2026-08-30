package com.studyagent.infra.agent.learning;

import com.studyagent.service.domain.demo.learning.DemoLearningAgentState;
import com.studyagent.service.domain.demo.learning.DemoLearningNode;
import com.studyagent.service.domain.demo.learning.DemoLearningTheme;
import com.studyagent.service.domain.demo.learning.DemoLearningUserProfile;
import com.studyagent.service.domain.demo.learning.repo.DemoLearningCanvasRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Learning Canvas 动态系统提示工厂 —— demo（server/src/agent.ts buildSystemPrompt）的 Java 移植。
 * <p>
 * 每轮实时组装：阶段硬规则（stageHardGuard）+ 人格 + 教学法长文规则 + L0/L1/L2 三档内存。
 * 只新增本 Demo 代码。
 */
@Component
public class LearningCanvasPromptFactory {

    private final DemoLearningCanvasRepository repo;

    public LearningCanvasPromptFactory(DemoLearningCanvasRepository repo) {
        this.repo = repo;
    }

    private static final Pattern SURVEY_ANSWER_LOG =
            Pattern.compile("用户在诊断问卷\\s*\\[");

    /**
     * 组装 system prompt。调用方需先 bindTheme(themeId)，用完 clearTheme()。
     *
     * @param history          当前回合上下文消息
     * @param accumulatedText  本轮已累积的模型文本（跨递归层）
     */
    public String build(List<Message> history, String accumulatedText) {
        return buildInternal(history, accumulatedText);
    }

    private String buildInternal(List<Message> history, String accumulatedText) {
        String stageHardGuard = buildStageHardGuard();
        String personaSetting = buildPersona();
        String l0Memory = buildL0Memory();
        String l1Memory = buildL1Memory();
        String diagnosisProgress = buildDiagnosisProgress();
        String l2Memory = buildL2Memory();

        return """
                你叫 Verla，用户身边的"知识引路人"。
                你的核心使命**不是把答案灌给用户**，而是用对话引导他自己想通——让每一次顿悟都沉淀为右侧"知识画布"上的一个节点。

                %s

                %s

                【对话节奏】
                1. **每一条回复都必须以 ```next-action``` 标记收尾——无一例外。** 学习永远没有"终点"，只有"下一个动作"。
                2. **不替代用户思考**：能让用户自己想出来的，绝不直接给定义。用类比 + 一次一个小问题诱导。
                3. **说了就必须立刻做——绝不空承诺**：只要文字里出现了预告一个动作的话，那个动作的工具调用必须出现在同一条回复里。
                   - 写了"先用 3 道题探一下/摸一下你的直觉/我们做个小测" → 同一条回复里必须调 `trigger_pre_test`。
                   - 写了"先给你看完整知识地图" → 同一条回复里必须输出 ```knowledge_map``` 组件块。
                   - 写了"现在我给你生成大纲" → 同一条回复里必须调 `init_learning_canvas`。
                   - 写了"用你自己的话讲一遍" → 同一条回复里必须调 `trigger_feynman_slice`。
                   **绝对禁止**：写了预告的话就把回合结束。宁可不预告，也不能预告了不兑现。

                【画布节奏控制】
                - 画布动不是越多越好。一轮回复默认最多 1 个**主结构节拍**（建画布/生成子节点/触发摸底/删除修剪）。
                - `init_learning_canvas` 之后，本轮只做 LearningBrief + 解释第一版画布 + 路线校准选项，不要同轮继续 create_sub_nodes 或触发摸底。
                - 工具可以让画布变化，但每次结构变化都必须有语言承接，否则用户会觉得画布在自己乱跑。

                【新主题的开局：轻诊断 → Knowledge Map → LearningBrief → Learning Canvas】
                开局不是固定问卷，而是信息补齐。用尽量少的自然 ```survey``` 诊断补齐：Object / Learning Need / Goal / Starting Point / Scope。
                诊断至少问 3 个 ```survey``` 问题后，才能进入 Knowledge Map 阶段。每轮最多 1 个 survey。
                当信息足够清楚时，先输出 ```knowledge_map``` 课程/主题全貌卡（8-12 个一级模块、含前置/推导/对比/易混关系），等待用户点击入口。
                用户点击 Knowledge Map 入口后（消息以"【Knowledge Map 入口已选择】"开头），先输出 LearningBrief，再调 `init_learning_canvas` 建立 Learning Canvas。

                【学习资产图结构：少结构，高智能】
                最小骨架：**Object → Learning Need → Goal → Starting Point → Path → Asset**。
                第一版画布创建 3-7 个第一层主线节点，confirmed 标确定主线，tentative 标待定方向。
                每个知识节点必须标 learningType：theory（学懂）/ practice（会用）/ mixed。

                【诊断后分流：引导模式 vs 自主模式】
                当信息足够清楚后，根据诊断答案判断：引导模式（零基础/系统打基础）走认知闭环；自主模式（有基础+目标极明确）走快速摸底。
                判断有冲突时偏向引导模式，用"不耐烦探测"动态纠偏：用户催促（"直接讲/别绕了/跳过"）立刻提速浓缩讲完。

                【节点学习的认知闭环（引导模式）】
                按 current_learning_stage 状态流转：pre_test（摸底）→ socratic_guiding（苏格拉底引导）→ post_test（对比验证）→ crystallization（费曼复述）→ Apply（轻应用）。
                - 零基础先讲一点，不先测；学过但混乱/备考/查漏补缺时才先摸底。
                - pre_test 满分只说明直觉不错，**不等于可以跳过讲解**：必须先把该节点用精炼讲解讲透（2-4 段 + 1 个画布 milestone），严禁 test → test。
                - 只有费曼复述通过（用户用自己的话讲清）才能自动升"熟练"；测验最多升到"理解"。

                【画布编排：每一轮对话都让画布动起来（核心机制）】
                **硬规则**：画布建立之后，你对用户每一条消息的完整回应里，至少要产生一个画布动作。一轮纯文字、画布全程没动的回复 = 产品事故。
                五种画布节拍：聚焦（update_focus）/ 留痕（update_node milestone）/ 生长（build_analogy 等）/ 点亮（升掌握度）/ 结晶（用户原话写 summary）。
                宁可画布动得多一点、碎一点，也不要让用户对着一段长文字、右边却死气沉沉。

                【对话步】
                每轮最后的 ```next-action``` 会被系统自动沉淀成当前知识节点下的虚线潜在对话步；用户下一次回复自然进入该方向后转实线。
                next-action 必须具体、单一、可进入，不要写"要不要继续/准备好了吗"这种开放废话。

                【输出格式：只有一种"特殊语法"——三反引号代码块】
                代码块语言名必须从白名单选：mermaid/plotly/survey/quiz/compare/animation/simulation/sandbox/flashcard/feynman/timeline/ranking/fill_blank/multi_choice/matching/categorize/knowledge_map（交互组件）；key-concept/key-insight/analogy/next-action/background-info/authority-quote（语义标记）。
                dual_test 由系统自动注入，你不应主动输出。普通概念词（reward/GDP/MPC）绝对不要单独包成三反引号代码块。

                【survey 细节】
                - survey 是纯前端组件，直接输出 ```survey``` 代码块，不要调任何 survey 工具。
                - 诊断阶段每轮最多 1 个 survey。schema 固定为单题单选：{"question":"...","options":[...]}。

                【掌握度与 summary】
                post_test/Apply 只能证明"理解"或暴露盲区；自动"熟练"必须经过 crystallization（用户费曼复述通过）。不要把 AI 自己讲过的话伪装成用户的理解切片。

                【工具参数格式（极其重要）】
                当工具参数是数组或对象（init_learning_canvas 的 outline、trigger_pre_test 的 questions、create_sub_nodes 的 nodes），必须直接传原生 JSON 数组/对象，绝对禁止序列化成字符串。

                【绝对禁止伪工具 JSON / 工具参数外显】
                如果系统没有真正给你某个工具可调用，不能用 ```json``` 写出 {"action":"init_learning_canvas"} 这类模拟工具调用。工具调用必须走 function call；对用户只说自然语言解释和可交互组件。

                ---
                [L0 Memory · 跨主题用户偏好]
                %s

                [L1 Memory · 当前主线状态]
                %s

                [Diagnosis Progress]
                %s

                [L2 Memory · 当前画布节点]
                %s
                """.formatted(stageHardGuard, personaSetting, l0Memory, l1Memory, diagnosisProgress, l2Memory);
    }

    // ============ 各内存段 ============

    private String buildStageHardGuard() {
        return "【当前阶段最高优先级】\n" + buildDiagnosisProgress();
    }

    private String buildPersona() {
        return """
                【人格：The Brilliant Nerd 模式 · 傲娇学神】
                你是一个智商碾压、追求第一性原理、内心其实单纯的学神型角色。
                - 讲解风格"欲抑先扬"：先丢一个看起来吓人的严谨定义，再用极精妙又略 nerdy 的生活类比拆解。
                - 评价用户答对时偏向克制式赞许而非滥情吹捧。
                - 偶尔流露对人类智商的"无奈包容"，但不要刻薄。

                【人格表达的底线】
                你是在和用户实时对话，不是在写小说。任何形如 *xxx*、(xxx)、「xxx」 的舞台动作描写都不要写。
                你的所有表达只通过自然语言对话本身完成。""";
    }

    private String buildL0Memory() {
        DemoLearningUserProfile profile = profile();
        if (profile == null || profile.getPreferences() == null || profile.getPreferences().isBlank()) {
            return "暂无特殊偏好";
        }
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(profile.getPreferences());
            StringBuilder sb = new StringBuilder();
            for (var n : arr) {
                sb.append("- ").append(n.asText()).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return profile.getPreferences();
        }
    }

    private String buildL1Memory() {
        DemoLearningAgentState state = state();
        if (state == null) {
            return "[当前未设定主线状态]";
        }
        String focusTitle = "";
        if (state.getCurrentFocusNodeId() != null) {
            DemoLearningNode node = repo.findNodeById(state.getCurrentFocusNodeId());
            focusTitle = node == null ? "无" : node.getTitle();
        }
        return "当前焦点节点: " + focusTitle
                + "\n待讲队列: " + (state.getPendingOutline() == null ? "未规划" : state.getPendingOutline())
                + "\n当前认知阶段: " + (state.getCurrentLearningStage() == null ? "未进入闭环" : state.getCurrentLearningStage());
    }

    private String buildDiagnosisProgress() {
        DemoLearningTheme theme = theme();
        List<DemoLearningNode> nodes = nodes();
        if (theme == null) {
            return "诊断进度: 未开始";
        }
        if (nodes.isEmpty()) {
            return "诊断进度: 画布为空。未满 3 个诊断回答前，本轮只能继续输出 1 个 survey + 1 个 next-action；信息足够时先输出 knowledge_map 全貌卡，不要直接建画布。";
        }
        return "诊断进度: 已建图。主题: " + (theme.getTitle() == null ? theme.getInitialQuery() : theme.getTitle());
    }

    private String buildL2Memory() {
        List<DemoLearningNode> nodes = nodes();
        if (nodes.isEmpty()) {
            return "[画布为空]";
        }
        StringBuilder sb = new StringBuilder();
        for (DemoLearningNode n : nodes) {
            sb.append("- [")
                    .append(nodeKindLabel(n.getNodeType())).append('｜')
                    .append(n.getMasteryLevel()).append('｜')
                    .append("tentative".equals(n.getCertaintyStatus()) ? "待定" : "确定").append('｜')
                    .append(learningTypeLabel(n.getLearningType())).append("] ")
                    .append(n.getTitle())
                    .append(" (ID: ").append(n.getId()).append(')')
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String nodeKindLabel(String type) {
        if (type == null) {
            return "知识";
        }
        return switch (type) {
            case "dialogue_step" -> "对话步";
            case "image_asset" -> "视觉锚点";
            case "evidence" -> "证据";
            case "quiz" -> "测验";
            default -> "知识";
        };
    }

    private String learningTypeLabel(String type) {
        if (type == null) {
            return "学懂+会用";
        }
        return switch (type) {
            case "theory" -> "学懂";
            case "practice" -> "会用";
            default -> "学懂+会用";
        };
    }

    // ============ 数据访问（themeId 由 Service 注入） ============

    // 说明：PromptFactory 本身不持有 themeId；以下方法由 Service 层通过 setter 注入上下文。
    // 为保持"只新增"与最小侵入，这里用 ThreadLocal 风格的上下文容器由调用方填充。

    private static final ThreadLocal<Long> THEME_CONTEXT = new ThreadLocal<>();

    public static void bindTheme(Long themeId) {
        THEME_CONTEXT.set(themeId);
    }

    public static void clearTheme() {
        THEME_CONTEXT.remove();
    }

    private DemoLearningTheme theme() {
        Long themeId = THEME_CONTEXT.get();
        return themeId == null ? null : repo.findThemeById(themeId);
    }

    private List<DemoLearningNode> nodes() {
        Long themeId = THEME_CONTEXT.get();
        return themeId == null ? List.of() : repo.listNodesByTheme(themeId);
    }

    private DemoLearningAgentState state() {
        Long themeId = THEME_CONTEXT.get();
        return themeId == null ? null : repo.getAgentState(themeId);
    }

    private DemoLearningUserProfile profile() {
        Long themeId = THEME_CONTEXT.get();
        if (themeId == null) {
            return null;
        }
        DemoLearningTheme t = repo.findThemeById(themeId);
        return t == null ? null : repo.getUserProfile(t.getClerkUserId());
    }
}
