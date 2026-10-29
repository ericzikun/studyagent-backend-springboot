package com.studyagent.service.application.verla.clone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * clone 的函数图渲染（plot.py 的 Java 版）。
 *
 * 函数图必须由确定性几何画出来，不能交给 LLM 猜坐标；这里保留原版的
 * 表达式白名单、采样、坐标轴/网格/图例与断点分段。
 */
public final class CloneGraphRenderer {

    private static final List<String> PALETTE = List.of("#2D7FF9", "#D95C4A", "#2FA36B", "#7B61E1");

    private CloneGraphRenderer() {
    }

    public static String render(JsonObject spec, int width, int height) {
        double[] domain = pair(spec, "domain", -5, 5);
        List<Fn> functions = new ArrayList<>();
        JsonArray rawFns = spec.has("functions") && spec.get("functions").isJsonArray()
                ? spec.getAsJsonArray("functions") : new JsonArray();
        for (int i = 0; i < rawFns.size(); i += 1) {
            JsonElement element = rawFns.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject fn = element.getAsJsonObject();
            try {
                DoubleUnaryOperator op = compile(normalizeExpr(str(fn, "expr", "x")));
                functions.add(new Fn(op,
                        str(fn, "color", PALETTE.get(i % PALETTE.size())),
                        str(fn, "label", str(fn, "expr", "y"))));
            } catch (Exception ignored) {
                // 一个坏表达式不影响其他曲线
            }
        }

        int samples = 480;
        double ymin = Double.POSITIVE_INFINITY;
        double ymax = Double.NEGATIVE_INFINITY;
        for (Fn fn : functions) {
            for (int i = 0; i <= samples; i += 1) {
                double x = domain[0] + (domain[1] - domain[0]) * i / samples;
                try {
                    double y = fn.fn.applyAsDouble(x);
                    if (Double.isFinite(y)) {
                        ymin = Math.min(ymin, y);
                        ymax = Math.max(ymax, y);
                    }
                } catch (Exception ignored) {
                    // 断点
                }
            }
        }
        if (!Double.isFinite(ymin)) {
            ymin = -5;
            ymax = 5;
        }
        JsonArray points = spec.has("points") && spec.get("points").isJsonArray()
                ? spec.getAsJsonArray("points") : new JsonArray();
        for (JsonElement element : points) {
            if (!element.isJsonObject()) {
                continue;
            }
            try {
                double y = element.getAsJsonObject().get("y").getAsDouble();
                ymin = Math.min(ymin, y);
                ymax = Math.max(ymax, y);
            } catch (Exception ignored) {
                // 坏点跳过
            }
        }
        double[] range = pair(spec, "range", Double.NaN, Double.NaN);
        if (Double.isNaN(range[0]) || Double.isNaN(range[1])) {
            double pad = Math.max((ymax - ymin) * 0.16, 0.6);
            range = new double[]{ymin - pad, ymax + pad};
        }
        if (range[1] - range[0] < 1e-6) {
            range = new double[]{range[0] - 1, range[1] + 1};
        }

        double padL = 46, padR = 18, padT = 20, padB = 34;
        double plotW = width - padL - padR;
        double plotH = height - padT - padB;
        double[] dom = domain;
        double[] rng = range;
        DoubleUnaryOperator sx = x -> padL + (x - dom[0]) / (dom[1] - dom[0]) * plotW;
        DoubleUnaryOperator sy = y -> padT + (rng[1] - y) / (rng[1] - rng[0]) * plotH;
        double stepX = niceStep((domain[1] - domain[0]) / 8);
        double stepY = niceStep((range[1] - range[0]) / 6);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width)
                .append(' ').append(height)
                .append("\" data-name=\"coordinate-plane\" font-family=\"Nunito, 'PingFang SC', sans-serif\">");
        svg.append("<defs><clipPath id=\"plotclip\"><rect x=\"").append(f(padL)).append("\" y=\"")
                .append(f(padT)).append("\" width=\"").append(f(plotW)).append("\" height=\"")
                .append(f(plotH)).append("\"/></clipPath>")
                .append("<marker id=\"axisarrow\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" ")
                .append("markerHeight=\"7\" orient=\"auto-start-reverse\"><path d=\"M1 1L9 5L1 9\" fill=\"none\" ")
                .append("stroke=\"#1F2A44\" stroke-width=\"1.6\" stroke-linecap=\"round\"/></marker></defs>");

        for (double x = Math.ceil(domain[0] / stepX) * stepX; x <= domain[1] + 1e-9; x += stepX) {
            double px = sx.applyAsDouble(x);
            svg.append("<line x1=\"").append(f(px)).append("\" y1=\"").append(f(padT)).append("\" x2=\"")
                    .append(f(px)).append("\" y2=\"").append(f(padT + plotH))
                    .append("\" stroke=\"#E7DFC8\" stroke-width=\"1\"/>");
            svg.append("<text x=\"").append(f(px)).append("\" y=\"").append(f(padT + plotH + 17))
                    .append("\" text-anchor=\"middle\" font-size=\"12\" fill=\"#7A7466\">")
                    .append(esc(fmt(x))).append("</text>");
        }
        for (double y = Math.ceil(range[0] / stepY) * stepY; y <= range[1] + 1e-9; y += stepY) {
            double py = sy.applyAsDouble(y);
            svg.append("<line x1=\"").append(f(padL)).append("\" y1=\"").append(f(py)).append("\" x2=\"")
                    .append(f(padL + plotW)).append("\" y2=\"").append(f(py))
                    .append("\" stroke=\"#E7DFC8\" stroke-width=\"1\"/>");
            svg.append("<text x=\"").append(f(padL - 9)).append("\" y=\"").append(f(py + 4))
                    .append("\" text-anchor=\"end\" font-size=\"12\" fill=\"#7A7466\">")
                    .append(esc(fmt(y))).append("</text>");
        }

        double axisY = sy.applyAsDouble(clamp(0, range[0], range[1]));
        double axisX = sx.applyAsDouble(clamp(0, domain[0], domain[1]));
        svg.append("<line x1=\"").append(f(padL)).append("\" y1=\"").append(f(axisY)).append("\" x2=\"")
                .append(f(padL + plotW)).append("\" y2=\"").append(f(axisY))
                .append("\" stroke=\"#1F2A44\" stroke-width=\"1.6\" marker-end=\"url(#axisarrow)\"/>");
        svg.append("<line x1=\"").append(f(axisX)).append("\" y1=\"").append(f(padT + plotH)).append("\" x2=\"")
                .append(f(axisX)).append("\" y2=\"").append(f(padT))
                .append("\" stroke=\"#1F2A44\" stroke-width=\"1.6\" marker-end=\"url(#axisarrow)\"/>");
        svg.append("<text x=\"").append(f(padL + plotW - 4)).append("\" y=\"").append(f(axisY - 8))
                .append("\" text-anchor=\"end\" font-size=\"14\" font-style=\"italic\" fill=\"#1F2A44\">")
                .append(esc(str(spec, "xLabel", "x"))).append("</text>");
        svg.append("<text x=\"").append(f(axisX + 9)).append("\" y=\"").append(f(padT + 6))
                .append("\" font-size=\"14\" font-style=\"italic\" fill=\"#1F2A44\">")
                .append(esc(str(spec, "yLabel", "y"))).append("</text>");

        svg.append("<g clip-path=\"url(#plotclip)\">");
        List<Fn> legend = new ArrayList<>();
        for (Fn fn : functions) {
            StringBuilder d = new StringBuilder();
            boolean open = false;
            boolean hasPoint = false;
            for (int i = 0; i <= samples; i += 1) {
                double x = domain[0] + (domain[1] - domain[0]) * i / samples;
                double y;
                try {
                    y = fn.fn.applyAsDouble(x);
                } catch (Exception e) {
                    open = false;
                    continue;
                }
                if (!Double.isFinite(y) || y < range[0] - 10 || y > range[1] + 10) {
                    open = false;
                    continue;
                }
                double px = sx.applyAsDouble(x);
                double py = sy.applyAsDouble(y);
                d.append(open ? 'L' : 'M').append(f(px)).append(' ').append(f(py));
                open = true;
                hasPoint = true;
            }
            if (hasPoint) {
                svg.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(fn.color)
                        .append("\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");
                legend.add(fn);
            }
        }
        svg.append("</g>");

        for (JsonElement element : points) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject point = element.getAsJsonObject();
            try {
                double px = sx.applyAsDouble(point.get("x").getAsDouble());
                double py = sy.applyAsDouble(point.get("y").getAsDouble());
                String color = str(point, "color", "#7B61E1");
                svg.append("<circle cx=\"").append(f(px)).append("\" cy=\"").append(f(py))
                        .append("\" r=\"6\" fill=\"").append(color)
                        .append("\" stroke=\"#FDFBF3\" stroke-width=\"2\"/>");
                svg.append("<text x=\"").append(f(px + 9)).append("\" y=\"").append(f(py - 8))
                        .append("\" font-size=\"14\" font-weight=\"700\" fill=\"#1F2A44\">")
                        .append(esc(str(point, "label", ""))).append("</text>");
            } catch (Exception ignored) {
                // 坏点跳过
            }
        }

        for (int i = 0; i < legend.size(); i += 1) {
            Fn fn = legend.get(i);
            double yy = padT + 14 + i * 18;
            svg.append("<g><line x1=\"").append(f(padL + 8)).append("\" y1=\"").append(f(yy))
                    .append("\" x2=\"").append(f(padL + 26)).append("\" y2=\"").append(f(yy))
                    .append("\" stroke=\"").append(fn.color)
                    .append("\" stroke-width=\"3\" stroke-linecap=\"round\"/>");
            svg.append("<text x=\"").append(f(padL + 32)).append("\" y=\"").append(f(yy + 4))
                    .append("\" font-size=\"13\" font-weight=\"700\" fill=\"#1F2A44\">")
                    .append(esc(fn.label)).append("</text></g>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    // ── expression parser ────────────────────────────────────────────────

    static DoubleUnaryOperator compile(String raw) {
        List<Token> tokens = tokenize(normalizeExpr(raw));
        Parser parser = new Parser(tokens);
        Expr expr = parser.parse();
        if (!parser.done()) {
            throw new IllegalArgumentException("trailing tokens");
        }
        return x -> expr.eval(x);
    }

    private static String normalizeExpr(String expr) {
        String s = expr == null ? "" : expr.trim();
        s = s.replaceAll("^\\s*(?:y|f\\s*\\(\\s*x\\s*\\))\\s*=", "");
        s = s.replace("²", "^2").replace("³", "^3").replace("×", "*").replace("−", "-").replace("π", "pi");
        s = s.replaceAll("(?<=[0-9])(?=[a-zA-Z(])", "*");
        s = s.replaceAll("(?<=\\))(?=[a-zA-Z0-9(])", "*");
        return s;
    }

    private static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char ch = src.charAt(i);
            if (Character.isWhitespace(ch)) {
                i += 1;
                continue;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int j = i;
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) {
                    j += 1;
                }
                tokens.add(new Token("num", Double.parseDouble(src.substring(i, j)), null));
                i = j;
                continue;
            }
            if (Character.isLetter(ch)) {
                int j = i;
                while (j < src.length() && Character.isLetter(src.charAt(j))) {
                    j += 1;
                }
                tokens.add(new Token("id", 0, src.substring(i, j)));
                i = j;
                continue;
            }
            if ("+-*/^()".indexOf(ch) >= 0) {
                tokens.add(new Token(String.valueOf(ch), 0, null));
                i += 1;
                continue;
            }
            throw new IllegalArgumentException("unsupported token " + ch);
        }
        return tokens;
    }

    private record Token(String kind, double value, String id) {
    }

    private interface Expr {
        double eval(double x);
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean done() {
            return pos == tokens.size();
        }

        Expr parse() {
            Expr node = term();
            while (peek() != null && (peek().kind.equals("+") || peek().kind.equals("-"))) {
                String op = tokens.get(pos++).kind;
                Expr left = node;
                Expr right = term();
                node = x -> op.equals("+") ? left.eval(x) + right.eval(x) : left.eval(x) - right.eval(x);
            }
            return node;
        }

        private Expr term() {
            Expr node = factor();
            while (peek() != null && (peek().kind.equals("*") || peek().kind.equals("/"))) {
                String op = tokens.get(pos++).kind;
                Expr left = node;
                Expr right = factor();
                node = x -> op.equals("*") ? left.eval(x) * right.eval(x) : left.eval(x) / right.eval(x);
            }
            return node;
        }

        private Expr factor() {
            Expr base = unary();
            if (peek() != null && peek().kind.equals("^")) {
                pos += 1;
                Expr exponent = factor();
                return x -> Math.pow(base.eval(x), exponent.eval(x));
            }
            return base;
        }

        private Expr unary() {
            if (peek() != null && peek().kind.equals("-")) {
                pos += 1;
                Expr inner = unary();
                return x -> -inner.eval(x);
            }
            if (peek() != null && peek().kind.equals("+")) {
                pos += 1;
                return unary();
            }
            return primary();
        }

        private Expr primary() {
            Token token = peek();
            if (token == null) {
                throw new IllegalArgumentException("unexpected end");
            }
            if (token.kind.equals("num")) {
                pos += 1;
                return x -> token.value;
            }
            if (token.kind.equals("id")) {
                pos += 1;
                String name = token.id;
                if (name.equals("x")) {
                    return x -> x;
                }
                if (name.equals("pi")) {
                    return x -> Math.PI;
                }
                if (name.equals("e")) {
                    return x -> Math.E;
                }
                if (name.equals("(")) {
                    throw new IllegalArgumentException("bad function");
                }
                eat("(");
                Expr arg = parse();
                eat(")");
                return switch (name) {
                    case "sin" -> x -> Math.sin(arg.eval(x));
                    case "cos" -> x -> Math.cos(arg.eval(x));
                    case "tan" -> x -> Math.tan(arg.eval(x));
                    case "sqrt" -> x -> Math.sqrt(arg.eval(x));
                    case "abs" -> x -> Math.abs(arg.eval(x));
                    case "exp" -> x -> Math.exp(arg.eval(x));
                    case "ln" -> x -> Math.log(arg.eval(x));
                    case "log" -> x -> Math.log10(arg.eval(x));
                    default -> throw new IllegalArgumentException("unknown identifier " + name);
                };
            }
            if (token.kind.equals("(")) {
                pos += 1;
                Expr node = parse();
                eat(")");
                return node;
            }
            throw new IllegalArgumentException("unexpected token " + token.kind);
        }

        private void eat(String kind) {
            Token token = peek();
            if (token == null || !token.kind.equals(kind)) {
                throw new IllegalArgumentException("expected " + kind);
            }
            pos += 1;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Fn(DoubleUnaryOperator fn, String color, String label) {
    }

    private static double[] pair(JsonObject obj, String key, double d0, double d1) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray(key);
            if (arr.size() >= 2) {
                try {
                    return new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble()};
                } catch (Exception ignored) {
                    // fallthrough
                }
            }
        }
        return new double[]{d0, d1};
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

    private static double niceStep(double raw) {
        if (!(raw > 0) || !Double.isFinite(raw)) {
            return 1;
        }
        double power = Math.pow(10, Math.floor(Math.log10(raw)));
        double base = raw / power;
        double mult = base <= 1 ? 1 : base <= 2 ? 2 : base <= 5 ? 5 : 10;
        return mult * power;
    }

    private static String fmt(double n) {
        if (Math.abs(n) < 1e-9) {
            return "0";
        }
        if (Math.abs(n) >= 100) {
            return String.format(Locale.ROOT, "%.0f", n);
        }
        return String.format(Locale.ROOT, "%.2f", n).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
