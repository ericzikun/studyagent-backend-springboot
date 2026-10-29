package com.studyagent.service.application.verla.clone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** clone 的示意图 scene 渲染与兜底卡片（diagram.py 的确定性部分）。 */
public final class CloneDiagramRenderer {

    private static final String FONT = "font-family=\"'Ma Shan Zheng','Kaiti SC',serif\"";
    private static final String UI_FONT = "font-family=\"Nunito,'PingFang SC',sans-serif\"";
    private static final Pattern FULL_CANVAS_RECT = Pattern.compile("<rect\\b[^>]*?/?>");
    private static final Pattern ATTR = Pattern.compile("(x|y|width|height|rx|ry)=\"([0-9.]+)\"");

    private CloneDiagramRenderer() {
    }

    static String color(JsonObject item) {
        String raw = item.has("color") && !item.get("color").isJsonNull()
                ? item.get("color").getAsString() : "ink";
        return switch (raw) {
            case "blue", "red", "green", "purple", "sun", "gray", "ink" -> switch (raw) {
                case "blue" -> "#2D7FF9";
                case "red" -> "#D95C4A";
                case "green" -> "#2FA36B";
                case "purple" -> "#7B61E1";
                case "sun" -> "#F0B429";
                case "gray" -> "#77746A";
                default -> "#1F2A44";
            };
            default -> raw.startsWith("#") && (raw.length() == 4 || raw.length() == 7) ? raw : "#1F2A44";
        };
    }

    public static String renderScene(JsonObject scene) {
        int width = 440;
        int height = 330;
        JsonArray items = scene.has("items") && scene.get("items").isJsonArray()
                ? scene.getAsJsonArray("items") : new JsonArray();
        List<String> colors = new ArrayList<>();
        colors.add("#1F2A44");
        for (JsonElement element : items) {
            if (element.isJsonObject()) {
                String c = color(element.getAsJsonObject());
                if (!colors.contains(c)) {
                    colors.add(c);
                }
            }
        }
        colors.sort(String::compareTo);

        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"")
                .append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" data-name=\"scene-diagram\"><defs>");
        for (int i = 0; i < colors.size(); i += 1) {
            String c = colors.get(i);
            out.append("<marker id=\"arr").append(i).append("\" data-color=\"").append(c)
                    .append("\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" ")
                    .append("orient=\"auto-start-reverse\"><path d=\"M1 1L9 5L1 9\" fill=\"none\" stroke=\"")
                    .append(c).append("\" stroke-width=\"2\" stroke-linecap=\"round\"/></marker>");
        }
        out.append("</defs>");
        String title = scene.has("title") && !scene.get("title").isJsonNull()
                ? scene.get("title").getAsString().trim() : "";
        if (!title.isEmpty()) {
            out.append("<text x=\"22\" y=\"30\" ").append(FONT)
                    .append(" font-size=\"20\" fill=\"#1F2A44\">").append(esc(title)).append("</text>");
        }
        int used = 0;
        for (JsonElement element : items) {
            if (used >= 12 || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            used += 1;
            String kind = str(item, "type", "");
            String c = color(item);
            switch (kind) {
                case "box" -> {
                    double x = num(item, "x", 40);
                    double y = num(item, "y", 60);
                    double w = clamp(num(item, "w", 120), 40, 380);
                    double h = clamp(num(item, "h", 56), 34, 200);
                    out.append("<rect x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" width=\"")
                            .append(f(w)).append("\" height=\"").append(f(h))
                            .append("\" rx=\"12\" fill=\"").append(c)
                            .append("\" fill-opacity=\"0.10\" stroke=\"").append(c)
                            .append("\" stroke-width=\"2.2\"/>");
                    List<String> lines = wrap(str(item, "text", ""), 7);
                    double lineH = 18;
                    double startY = y + h / 2 - (lines.size() - 1) * lineH / 2 + 6;
                    for (int i = 0; i < lines.size(); i += 1) {
                        out.append("<text x=\"").append(f(x + w / 2)).append("\" y=\"")
                                .append(f(startY + i * lineH))
                                .append("\" text-anchor=\"middle\" ").append(FONT)
                                .append(" font-size=\"15\" fill=\"#1F2A44\">").append(esc(lines.get(i)))
                                .append("</text>");
                    }
                }
                case "circle" -> {
                    double cx = num(item, "cx", 220);
                    double cy = num(item, "cy", 170);
                    double r = clamp(num(item, "r", 44), 20, 80);
                    out.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy))
                            .append("\" r=\"").append(f(r))
                            .append("\" fill=\"#FFFDF5\" stroke=\"").append(c)
                            .append("\" stroke-width=\"2.6\"/>");
                    out.append("<text x=\"").append(f(cx)).append("\" y=\"").append(f(cy + r + 22))
                            .append("\" text-anchor=\"middle\" ").append(FONT)
                            .append(" font-size=\"15\" fill=\"#1F2A44\">")
                            .append(esc(trunc(str(item, "text", ""), 12))).append("</text>");
                }
                case "arrow" -> {
                    double x1 = num(item, "x1", 0);
                    double y1 = num(item, "y1", 0);
                    double x2 = num(item, "x2", 0);
                    double y2 = num(item, "y2", 0);
                    String marker = "arr" + Math.max(0, colors.indexOf(c));
                    String dash = item.has("dashed") && item.get("dashed").getAsBoolean()
                            ? " stroke-dasharray=\"6 6\"" : "";
                    out.append("<line x1=\"").append(f(x1)).append("\" y1=\"").append(f(y1)).append("\" x2=\"")
                            .append(f(x2)).append("\" y2=\"").append(f(y2))
                            .append("\" stroke=\"").append(c)
                            .append("\" stroke-width=\"2.6\" stroke-linecap=\"round\" marker-end=\"url(#")
                            .append(marker).append(")\"").append(dash).append("/>");
                    String label = str(item, "label", "");
                    if (!label.isEmpty()) {
                        out.append("<text x=\"").append(f((x1 + x2) / 2)).append("\" y=\"")
                                .append(f((y1 + y2) / 2 - 8))
                                .append("\" text-anchor=\"middle\" ").append(FONT)
                                .append(" font-size=\"13\" fill=\"").append(c).append("\">")
                                .append(esc(trunc(label, 12))).append("</text>");
                    }
                }
                case "line" -> {
                    double x1 = num(item, "x1", 0);
                    double y1 = num(item, "y1", 0);
                    double x2 = num(item, "x2", 0);
                    double y2 = num(item, "y2", 0);
                    String dash = item.has("dashed") && item.get("dashed").getAsBoolean()
                            ? " stroke-dasharray=\"6 6\"" : "";
                    out.append("<line x1=\"").append(f(x1)).append("\" y1=\"").append(f(y1)).append("\" x2=\"")
                            .append(f(x2)).append("\" y2=\"").append(f(y2)).append("\" stroke=\"").append(c)
                            .append("\" stroke-width=\"2\"").append(dash).append("/>");
                }
                case "label" -> {
                    double x = num(item, "x", 40);
                    double y = num(item, "y", 40);
                    int size = (int) clamp(num(item, "size", 14), 11, 22);
                    out.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" ")
                            .append(FONT).append(" font-size=\"").append(size).append("\" fill=\"").append(c)
                            .append("\">").append(esc(trunc(str(item, "text", ""), 20))).append("</text>");
                }
                default -> {
                    // 未知元素忽略
                }
            }
        }
        out.append("</svg>");
        return out.toString();
    }

    public static String fallback(String text, String title) {
        List<String> lines = wrap(text, 18);
        if (lines.size() > 4) {
            lines = lines.subList(0, 4);
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i += 1) {
            body.append("<text x=\"220\" y=\"").append(170 + i * 24)
                    .append("\" text-anchor=\"middle\" ").append(FONT)
                    .append(" font-size=\"14\" fill=\"#77746A\">").append(esc(lines.get(i))).append("</text>");
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"440\" height=\"330\" viewBox=\"0 0 440 330\" "
                + "data-name=\"diagram-fallback\"><rect x=\"16\" y=\"16\" width=\"408\" height=\"298\" rx=\"18\" "
                + "fill=\"#FFFDF5\" stroke=\"#1F2A44\" stroke-width=\"2\" stroke-dasharray=\"7 7\"/>"
                + "<text x=\"220\" y=\"120\" text-anchor=\"middle\" " + FONT
                + " font-size=\"22\" fill=\"#1F2A44\">" + esc(title) + "</text>" + body + "</svg>";
    }

    public static String stripFullCanvasFrames(String svg) {
        Matcher matcher = FULL_CANVAS_RECT.matcher(svg);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String tag = matcher.group();
            Matcher attrs = ATTR.matcher(tag);
            double x = 0, y = 0, w = 0, h = 0;
            while (attrs.find()) {
                double v;
                try {
                    v = Double.parseDouble(attrs.group(2));
                } catch (NumberFormatException e) {
                    v = 0;
                }
                switch (attrs.group(1)) {
                    case "x" -> x = v;
                    case "y" -> y = v;
                    case "width" -> w = v;
                    case "height" -> h = v;
                    default -> {
                        // rx/ry 不参与判断
                    }
                }
            }
            matcher.appendReplacement(out, w >= 400 && h >= 285 && x <= 20 && y <= 20 ? "" : Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean valid(String svg) {
        if (svg == null) {
            return false;
        }
        String low = svg.toLowerCase(Locale.ROOT);
        return low.startsWith("<svg") && low.contains("</svg>")
                && !low.contains("<script") && !low.contains("<foreignobject")
                && !low.contains("href=\"http") && !low.contains("xlink:href=\"http")
                && svg.length() <= 60000;
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("\n")) {
            if (part.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < part.length(); i += 1) {
                line.append(part.charAt(i));
                if (line.length() >= width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static double num(JsonObject obj, String key, double fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
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

    private static String trunc(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
