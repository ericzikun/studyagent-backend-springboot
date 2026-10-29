package com.studyagent.service.application.verla.clone;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ahaboard-clone agent 的 Java 复刻。
 *
 * 输出同一套 JSON Lines：speak / write_text / write_latex / draw / highlight /
 * circle_annotate / erase；draw 的 diagram 走 LLM SVG，graph 走确定性函数图。
 * 这样前端可以逐字使用 clone 的播放器与白板，不需要 Python 的 board 协议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTutorCloneAgentService {

    private static final Set<String> ALLOWED = Set.of(
            "speak", "write_text", "write_latex", "draw", "highlight",
            "circle_annotate", "erase", "finish_lesson");
    private static final Gson GSON = new Gson();

    private final VideoTutorCloneProperties props;
    private final CloneLlmClient llm;
    private final CloneAssetStore assetStore;
    private final ExecutorService diagramExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "video-tutor-clone-diagram");
        thread.setDaemon(true);
        return thread;
    });

    private static final String SYSTEM_PROMPT = """
            你是 ahaboard 风格的语音白板家教：一边讲，一边把关键内容写到白板上。

            你只能输出 JSON Lines：每行一个 JSON 对象。不要 markdown、不要代码块、不要任何解释文字。

            可用命令：
            1. {"commandName":"speak","orderIndex":0,"text":"口语化的一句话，不超过60字","style":"cheerful"}
            2. {"commandName":"write_text","orderIndex":1,"elementId":"title","position":{"spaceId":"lesson","columnId":"main"},"text":"<h1>标题</h1>"}
            3. {"commandName":"write_latex","orderIndex":2,"elementId":"formula","position":{"spaceId":"lesson","columnId":"main"},"latex":"F = ma","color":"#2D7FF9"}
            4. {"commandName":"draw","orderIndex":3,"elementId":"graph1","kind":"graph","position":{"spaceId":"lesson","columnId":"visual"},"spec":{"functions":[{"expr":"x^2","label":"y = x²"}],"domain":[-3,4],"points":[{"x":1,"y":1,"label":"A"}],"xLabel":"x","yLabel":"y"},"width":440,"height":330}
            5. {"commandName":"draw","orderIndex":4,"elementId":"pic1","kind":"diagram","position":{"spaceId":"lesson","columnId":"visual"},"prompt":"俯视的旋转足球：球在中心，速度向上，逆时针旋转箭头，蓝色马格努斯力向左，红色空气阻力向下，标注两侧气流快慢与压强","width":420,"height":320}
            6. {"commandName":"highlight","orderIndex":5,"targetElementId":"key1","textToMatch":"马格努斯效应","color":"yellow"}
            7. {"commandName":"circle_annotate","orderIndex":6,"targetElementId":"formula","textToMatch":"F = ma","color":"blue"}

            硬性规则：
            - 本轮输出 8-10 条命令：至少 3 条 speak；必须写 1 个 h1 标题 + 1 个「今日路线」块 + 2-4 个要点/公式块；按需 1 张示意图或函数图；至少 2 次 highlight / circle_annotate。板书要把画面填满，但不能互相压住。
            - 先说话，再板书；speak 是自然口语，不要念符号，不要书面语。
            - 板书要短：标题用 <h1>，要点用 <b> 和 <br>，步骤用 <ol><li>；数学公式一律用 write_latex，不要塞进 write_text。
            - 需要示意图时用 kind:"diagram"，prompt 用中文把画面描述清楚（对象、箭头方向、标注文字、相对位置）；绝对不要自己输出 SVG。
            - 需要函数图像时用 kind:"graph"，spec.expr 支持 x^2、2*x+3、sin(x)。
            - highlight / circle_annotate **必须带 textToMatch**：填白板上真实出现的、要强调的最短短语（≤12 字）；绝对不要整段高亮。
            - 布局：main 列只放「标题 + 今日路线 + 最多 1 个要点」；其余要点和公式放 notes；示意图/函数图放 visual。不要把 4 个以上元素堆在 main 列。
            - spaceId 固定写 lesson（一节会话只有一块白板，不要换名字）；columnId 只用 main、visual、notes。
            - 最后一条 speak 必须是检查理解的问题，以？结尾；不要输出 finish_lesson。
            - 只输出 JSON Lines，行与行之间不要空行。""";

    private static final String SCENE_SYSTEM = """
            你是教学示意图编排器。把老师要画的图转成严格 JSON（不要 markdown，不要解释）。
            画布 440x330，四周留 20 边距。只输出一个 JSON 对象：
            {"title":"图标题（≤10字，可空）","items":[...]}
            可用元素（最多 10 个）：
            - {"type":"box","x":40,"y":60,"w":150,"h":60,"text":"自旋","color":"blue"}
            - {"type":"circle","cx":220,"cy":180,"r":45,"text":"球","color":"ink"}
            - {"type":"arrow","x1":190,"y1":90,"x2":280,"y2":90,"label":"带动","color":"purple"}
            - {"type":"line","x1":40,"y1":250,"x2":400,"y2":250,"color":"gray","dashed":true}
            - {"type":"label","x":40,"y":300,"text":"气流快 · 低压","color":"gray"}
            颜色只能用 ink/blue/red/green/purple/sun/gray。文字≤12字，箭头方向要明确，布局不要重叠。""";

    private static final String SVG_SYSTEM = """
            You are an expert SVG illustrator for a hand-drawn educational whiteboard.
            Output ONLY one complete <svg>...</svg> document. No markdown, no code fences, no commentary.
            Canvas 440x330, viewBox="0 0 440 330". Transparent background.
            Style: warm hand-drawn textbook sketch, ink #1F2A44, accents #2D7FF9 #D95C4A #7B61E1, stroke-width 2.4, round caps.
            Use <defs><marker> arrowheads with markerWidth="6" markerHeight="6".
            Labels in Chinese, font-family="'Ma Shan Zheng','Kaiti SC',serif", 16-20px, high contrast.
            Composition: 6-12 shapes, use the full canvas, central object radius 50-60, clear arrows, 20px margins.
            Do NOT draw a card, frame, dashed border, or background rectangle around the whole diagram; the whiteboard itself is the background.
            Labels must NOT overlap each other, arrows, or shapes: leave at least 24px between a label and any line; put labels only in the four outer margins and use short wording. Keep the central 240x200 area free of text.
            No scripts, no foreignObject, no external images or refs.""";

    public boolean isReady() {
        return props.isEnabled() && llm.isReady();
    }

    public TurnResult run(TurnInput input, CloneEmitter emitter) {
        Turn turn = new Turn(input, emitter);
        List<CloneLlmClient.Provider> providers = llm.providers();
        if (providers.isEmpty()) {
            demo(turn);
            turn.finish("no-provider");
            return turn.result();
        }
        String lastError = "";
        for (CloneLlmClient.Provider provider : providers) {
            if (turn.stopped) {
                break;
            }
            try {
                turn.model = provider.model();
                streamProvider(turn, provider);
                if (turn.count == 0) {
                    throw new IOException("model produced no commands");
                }
                break;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[video-tutor/clone] provider {} failed: {}", provider.name(), e.getMessage());
                if (turn.count > 0) {
                    turn.send(command("speak", turn, Map.of(
                            "text", "抱歉，这一轮生成中断了。你可以再问我一次。",
                            "style", "chat")));
                    awaitDiagrams(turn);
                    turn.finish("provider-error");
                    return turn.result();
                }
                turn.resetForRetry();
            }
        }
        if (turn.count == 0) {
            log.warn("[video-tutor/clone] all providers failed: {}", lastError);
            demo(turn);
            awaitDiagrams(turn);
            turn.finish("demo-fallback");
        } else {
            awaitDiagrams(turn);
            turn.finish("");
        }
        return turn.result();
    }

    private void streamProvider(Turn turn, CloneLlmClient.Provider provider) throws IOException {
        StringBuilder buffer = new StringBuilder();
        llm.streamChat(provider, messages(turn.input), delta -> {
            buffer.append(delta);
            String text = buffer.toString();
            int newline;
            while ((newline = text.indexOf('\n')) >= 0) {
                String line = text.substring(0, newline);
                text = text.substring(newline + 1);
                buffer.setLength(0);
                buffer.append(text);
                handleLine(turn, line);
            }
        });
        if (!buffer.isEmpty()) {
            handleLine(turn, buffer.toString());
        }
    }

    private void handleLine(Turn turn, String raw) {
        if (turn.stopped) {
            return;
        }
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.equals("[") || line.equals("]")) {
            return;
        }
        line = line.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        if (line.isEmpty()) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(line);
        } catch (Exception e) {
            return;
        }
        if (parsed.isJsonArray()) {
            for (JsonElement item : parsed.getAsJsonArray()) {
                handleCommand(turn, item);
            }
        } else {
            handleCommand(turn, parsed);
        }
    }

    private void handleCommand(Turn turn, JsonElement element) {
        if (turn.stopped || turn.count >= props.getMaxTurnSteps() || !element.isJsonObject()) {
            return;
        }
        JsonObject cmd = normalize(element.getAsJsonObject(), turn);
        if (cmd == null) {
            return;
        }
        String name = cmd.get("commandName").getAsString();
        turn.ensureMeta();
        turn.count += 1;
        if ("draw".equals(name)) {
            String kind = cmd.get("kind").getAsString();
            if ("graph".equals(kind)) {
                streamGraphAndFinal(turn, cmd);
                return;
            }
            turn.send(cmd);
            scheduleDiagram(turn, cmd);
            return;
        }
        turn.send(cmd);
        if ("speak".equals(name) && cmd.has("text")) {
            turn.assistantText.append(cmd.get("text").getAsString());
        }
    }

    private JsonObject normalize(JsonObject raw, Turn turn) {
        String name = str(raw, "commandName", "");
        if (!ALLOWED.contains(name)) {
            return null;
        }
        JsonObject out = raw.deepCopy();
        int order = raw.has("orderIndex") && raw.get("orderIndex").isJsonPrimitive()
                ? raw.get("orderIndex").getAsInt() : turn.order;
        out.addProperty("orderIndex", order);
        turn.order = Math.max(turn.order, order + 1);

        // 模型偶尔把 write_latex 的内容写成 text/html：统一成前端约定的 latex 字段。
        if (name.equals("write_latex")) {
            String latex = str(out, "latex", "");
            if (latex.isBlank()) {
                latex = str(out, "text", "");
            }
            if (latex.isBlank()) {
                latex = str(out, "html", "");
            }
            out.remove("text");
            out.remove("html");
            if (latex.isBlank()) {
                return null;
            }
            out.addProperty("latex", latex);
        }
        if (name.equals("write_text")) {
            String html = str(out, "text", "");
            if (html.isBlank()) {
                html = str(out, "html", "");
            }
            out.remove("latex");
            out.remove("html");
            if (html.isBlank()) {
                return null;
            }
            out.addProperty("text", html);
        }

        if (name.equals("write_text") || name.equals("write_latex") || name.equals("draw")) {
            JsonObject position = out.has("position") && out.get("position").isJsonObject()
                    ? out.getAsJsonObject("position") : new JsonObject();
            // 一节会话只有一块白板，模型自己编的 spaceId 每轮都会变，统一钉死。
            String space = "lesson";
            String column = str(position, "columnId", name.equals("draw") ? "visual" : "main");
            if ((name.equals("write_text") || name.equals("write_latex")) && "main".equals(column)) {
                if (turn.mainCount >= 2) {
                    column = "notes";
                } else {
                    turn.mainCount += 1;
                }
            }
            position.addProperty("spaceId", space);
            position.addProperty("columnId", column);
            out.add("position", position);
        }
        if (name.equals("draw")) {
            String kind = str(out, "kind", "");
            if (!kind.equals("diagram") && !kind.equals("graph")) {
                return null;
            }
            if (!out.has("elementId") || str(out, "elementId", "").isBlank()) {
                out.addProperty("elementId", "draw_" + order);
            }
            out.addProperty("width", intOr(out, "width", kind.equals("graph") ? 440 : 420));
            out.addProperty("height", intOr(out, "height", kind.equals("graph") ? 330 : 320));
            if (out.has("final") && out.get("final").getAsBoolean()) {
                return null;
            }
            if (kind.equals("graph") && (!out.has("spec") || !out.get("spec").isJsonObject())) {
                return null;
            }
        }
        return out;
    }

    private void streamGraphAndFinal(Turn turn, JsonObject cmd) {
        turn.send(cmd);
        String elementId = cmd.get("elementId").getAsString();
        try {
            JsonObject spec = cmd.getAsJsonObject("spec");
            int width = cmd.get("width").getAsInt();
            int height = cmd.get("height").getAsInt();
            String svg = CloneGraphRenderer.render(spec, width, height);
            streamSvg(turn, elementId, svg);
            finalDraw(turn, cmd, svg, true, 0);
        } catch (Exception e) {
            log.warn("[video-tutor/clone] graph render failed: {}", e.getMessage());
        }
    }

    private void scheduleDiagram(Turn turn, JsonObject cmd) {
        Future<?> future = diagramExecutor.submit(() -> {
            long started = System.currentTimeMillis();
            String elementId = cmd.get("elementId").getAsString();
            String prompt = str(cmd, "prompt", "教学示意图");
            String svg = "";
            if (props.isDiagramEnabled()) {
                svg = rawSvg(turn, elementId, prompt);
            }
            if (svg.isBlank()) {
                svg = sceneSvg(prompt);
            }
            if (svg.isBlank()) {
                svg = CloneDiagramRenderer.fallback(prompt, "示意图");
                streamSvg(turn, elementId, svg);
            }
            finalDraw(turn, cmd, svg, false, System.currentTimeMillis() - started);
        });
        turn.diagramTasks.add(future);
    }

    private String rawSvg(Turn turn, String elementId, String prompt) {
        for (CloneLlmClient.Provider provider : llm.providers()) {
            StringBuilder pending = new StringBuilder();
            StringBuilder full = new StringBuilder();
            boolean[] started = {false};
            try {
                llm.streamChat(provider, List.of(
                        Map.of("role", "system", "content", SVG_SYSTEM),
                        Map.of("role", "user", "content", prompt)
                ), delta -> {
                    if (turn.stopped || full.indexOf("</svg>") >= 0) {
                        return;
                    }
                    if (!started[0]) {
                        pending.append(delta);
                        int index = pending.indexOf("<svg");
                        if (index < 0) {
                            return;
                        }
                        started[0] = true;
                        String chunk = pending.substring(index);
                        pending.setLength(0);
                        full.append(chunk);
                        emitSvgChunk(turn, elementId, chunk);
                        return;
                    }
                    full.append(delta);
                    emitSvgChunk(turn, elementId, delta);
                }, 0.5, 2200);
            } catch (Exception e) {
                log.info("[video-tutor/clone] raw svg provider {} failed: {}", provider.name(), e.getMessage());
            }
            String svg = full.toString();
            int end = svg.toLowerCase().indexOf("</svg>");
            if (end >= 0) {
                svg = svg.substring(0, end + 6);
            }
            if (started[0] && CloneDiagramRenderer.valid(svg)) {
                return CloneDiagramRenderer.stripFullCanvasFrames(svg);
            }
        }
        return "";
    }

    private String sceneSvg(String prompt) {
        for (CloneLlmClient.Provider provider : llm.providers()) {
            try {
                JsonObject scene = llm.chatJson(provider, List.of(
                        Map.of("role", "system", "content", SCENE_SYSTEM),
                        Map.of("role", "user", "content", "要画的示意图：" + prompt + "\n请输出 JSON。")
                ), 0.3, 650, Math.max(8, props.getDiagramTimeoutSeconds()));
                if (scene.has("items") && scene.get("items").isJsonArray()
                        && !scene.getAsJsonArray("items").isEmpty()) {
                    return CloneDiagramRenderer.renderScene(scene);
                }
            } catch (Exception e) {
                log.info("[video-tutor/clone] scene provider {} failed: {}", provider.name(), e.getMessage());
            }
        }
        return "";
    }

    private void finalDraw(Turn turn, JsonObject cmd, String svg, boolean coordinatePlane, long genMs) {
        if (turn.stopped) {
            return;
        }
        JsonObject finalCmd = cmd.deepCopy();
        finalCmd.addProperty("final", true);
        finalCmd.addProperty("fileType", "svg");
        finalCmd.addProperty("isCoordinatePlane", coordinatePlane);
        finalCmd.addProperty("genMs", genMs);
        String url = assetStore == null ? null : assetStore.storeSvg(svg, cmd.get("elementId").getAsString());
        if (url != null) {
            finalCmd.addProperty("url", url);
        }
        // 同时带正文：刷新时不依赖 OSS CORS，事件回放也能直接画出来。
        finalCmd.addProperty("svg", svg);
        turn.send(finalCmd);
    }

    private void emitSvgChunk(Turn turn, String elementId, String chunk) {
        if (turn.stopped || chunk == null || chunk.isEmpty()) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_stream", "svg");
        line.put("elementId", elementId);
        line.put("chunk", chunk);
        turn.send(line);
    }

    private void streamSvg(Turn turn, String elementId, String svg) {
        if (turn.stopped || svg == null || svg.isEmpty()) {
            return;
        }
        int step = 160;
        for (int i = 0; i < svg.length(); i += step) {
            if (turn.stopped) {
                return;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("_stream", "svg");
            line.put("elementId", elementId);
            line.put("chunk", svg.substring(i, Math.min(svg.length(), i + step)));
            turn.send(line);
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void awaitDiagrams(Turn turn) {
        for (Future<?> future : turn.diagramTasks) {
            try {
                future.get(Math.max(5, props.getDiagramTimeoutSeconds() + 5L), TimeUnit.SECONDS);
            } catch (Exception e) {
                future.cancel(true);
            }
        }
        turn.diagramTasks.clear();
    }

    private void demo(Turn turn) {
        turn.ensureMeta();
        turn.send(command("speak", turn, Map.of("text", "我们先从一个核心问题开始。", "style", "chat")));
        turn.send(command("write_text", turn, Map.of(
                "elementId", "title", "position", Map.of("spaceId", "lesson", "columnId", "main"),
                "text", "<h1>今天这节课</h1>")));
        turn.send(command("write_text", turn, Map.of(
                "elementId", "route", "position", Map.of("spaceId", "lesson", "columnId", "main"),
                "text", "<b>今日路线</b><br>1. 概念<br>2. 例子<br>3. 易错点")));
        turn.send(command("speak", turn, Map.of("text", "你能先说说，你已经知道哪些？", "style", "chat")));
        turn.count = 4;
    }

    private Map<String, Object> command(String name, Turn turn, Map<String, Object> fields) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("commandName", name);
        cmd.put("orderIndex", turn.order++);
        cmd.putAll(fields);
        cmd.putIfAbsent("position", Map.of("spaceId", "lesson", "columnId", "main"));
        turn.ensureMeta();
        return cmd;
    }

    private List<Map<String, String>> messages(TurnInput input) {
        String style = input.styleNote();
        String info = "{\"title\":" + GSON.toJson(input.title())
                + ",\"input\":" + GSON.toJson(input.userMessage())
                + ",\"mode\":\"topic\",\"language\":" + GSON.toJson(input.language())
                + ",\"student\":null}";
        return List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", "本轮要求：" + style
                        + "\n\n课程信息：" + info
                        + "\n\n已有对话与板书：\n" + (input.transcript().isBlank() ? "（新课程）" : input.transcript()))
        );
    }

    @PreDestroy
    void shutdown() {
        diagramExecutor.shutdownNow();
    }

    public interface CloneEmitter {
        void emit(Map<String, Object> line);
    }

    public record TurnInput(
            Long conversationId,
            String title,
            String userMessage,
            String language,
            String teachingStyle,
            String transcript) {

        String styleNote() {
            if ("self_paced".equals(teachingStyle) || "coach".equals(teachingStyle)) {
                return "本轮是讲座模式：连续讲授，不要等学生回答；每 3-4 步抛出一个检查问题但继续往下讲，最后留一个总问题。";
            }
            return "本轮是互动模式：讲一两步就停下来问一个问题，等学生回应再继续。";
        }
    }

    public record TurnResult(List<Map<String, Object>> commands, String assistantText, String model) {
    }

    private final class Turn {
        private final TurnInput input;
        private final CloneEmitter emitter;
        private final Object lock = new Object();
        private final StringBuilder assistantText = new StringBuilder();
        private final List<Map<String, Object>> commands = new ArrayList<>();
        private final List<Future<?>> diagramTasks = new ArrayList<>();
        private int order;
        private int count;
        private int mainCount;
        private boolean metaSent;
        private boolean stopped;
        private String model = "";

        Turn(TurnInput input, CloneEmitter emitter) {
            this.input = input;
            this.emitter = emitter;
        }

        void ensureMeta() {
            if (metaSent) {
                return;
            }
            metaSent = true;
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("_meta", "start");
            start.put("model", model);
            start.put("srvRecv", System.currentTimeMillis());
            start.put("srvSend", System.currentTimeMillis());
            send(start);
            send(Map.of("_meta", "planning"));
            send(Map.of("_meta", "pace", "talkWhileDrawing", true));
        }

        @SuppressWarnings("unchecked")
        void send(JsonObject line) {
            send(GSON.fromJson(line, Map.class));
        }

        void send(Map<String, Object> line) {
            if (stopped) {
                throw new UncheckedIOException(new IOException("client closed"));
            }
            synchronized (lock) {
                try {
                    emitter.emit(line);
                    if (line.containsKey("commandName")) {
                        commands.add(new LinkedHashMap<>(line));
                    }
                } catch (RuntimeException e) {
                    stopped = true;
                    throw e;
                }
            }
        }

        void finish(String note) {
            if (!metaSent) {
                ensureMeta();
            }
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("_meta", "end");
            end.put("commands", count);
            end.put("firstMs", 0);
            end.put("ms", 0);
            end.put("note", note);
            send(end);
        }

        void resetForRetry() {
            order = 0;
            count = 0;
            mainCount = 0;
            metaSent = false;
            commands.clear();
            assistantText.setLength(0);
        }

        TurnResult result() {
            return new TurnResult(commands, assistantText.toString(), model);
        }
    }

    private static int intOr(JsonObject obj, String key, int fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception ignored) {
                // fallthrough
            }
        }
        return fallback;
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsString();
            } catch (Exception ignored) {
                // fallthrough
            }
        }
        return fallback;
    }
}
