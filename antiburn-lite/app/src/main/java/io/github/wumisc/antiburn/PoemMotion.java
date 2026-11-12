package io.github.wumisc.antiburn;

import java.util.ArrayList;
import java.util.List;

/** Small safe expression engine + low-power position lookup table. */
public final class PoemMotion {
    public static final String DEFAULT_DURATION_EXPR = "max(1, (width + textWidth + 2*padding) / speed)";
    public static final String DEFAULT_POSITION_EXPR = "p";

    private PoemMotion() {}

    public static final class Vars {
        public double p;
        public double t;
        public double duration;
        public double speed;
        public double width;
        public double textWidth;
        public double padding;
        public double chars;
    }

    public interface Compiled {
        double eval(Vars vars);
    }

    public static Compiled compile(final String expression) {
        final String src = expression == null || expression.trim().isEmpty()
                ? DEFAULT_POSITION_EXPR : expression;
        Parser parser = new Parser(src);
        final Node root = parser.parseExpression();
        parser.skip();
        if (!parser.end()) throw new IllegalArgumentException("多余字符: " + src.substring(parser.pos()));
        return new Compiled() {
            @Override public double eval(Vars vars) { return root.eval(vars); }
        };
    }

    public static void validate(String expression, boolean position) {
        Compiled c = compile(expression);
        Vars v = new Vars();
        v.p = 0.5; v.t = 1.0; v.duration = 2.0; v.speed = 120.0;
        v.width = 1080.0; v.textWidth = 480.0; v.padding = 8.0; v.chars = 24.0;
        double value = c.eval(v);
        if (!Double.isFinite(value) || value < 0 || (position ? value > 1 : value <= 0)) {
            throw new IllegalArgumentException(position ? "位置公式必须落在 0..1" : "时长必须大于 0");
        }
    }

    public static float[] sample(Compiled compiled, int count, Vars vars) {
        int n = Math.max(2, count);
        float[] out = new float[n];
        Vars v = new Vars();
        v.speed = vars.speed; v.width = vars.width; v.textWidth = vars.textWidth;
        v.padding = vars.padding; v.chars = vars.chars; v.duration = vars.duration;
        for (int i = 0; i < n; i++) {
            double p = i / (double) (n - 1);
            v.p = p;
            v.t = p * vars.duration;
            double d = compiled.eval(v);
            if (!Double.isFinite(d)) d = p;
            out[i] = (float) Math.max(0, Math.min(1, d));
        }
        return out;
    }

    public static float lookup(float[] lut, double p) {
        if (lut == null || lut.length == 0) return (float) Math.max(0, Math.min(1, p));
        double x = Math.max(0, Math.min(1, p)) * (lut.length - 1);
        int i = (int) x;
        int j = Math.min(lut.length - 1, i + 1);
        float f = (float) (x - i);
        return lut[i] + (lut[j] - lut[i]) * f;
    }

    private interface Node { double eval(Vars v); }

    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s; }
        boolean end() { return i >= s.length(); }
        int pos() { return i; }

        Node parseExpression() { return parseAdd(); }

        private Node parseAdd() {
            Node left = parseMul();
            while (true) {
                skip();
                if (take('+')) { final Node a = left, b = parseMul(); left = v -> a.eval(v) + b.eval(v); }
                else if (take('-')) { final Node a = left, b = parseMul(); left = v -> a.eval(v) - b.eval(v); }
                else return left;
            }
        }

        private Node parseMul() {
            Node left = parseUnary();
            while (true) {
                skip();
                if (take('*')) { final Node a = left, b = parseUnary(); left = v -> a.eval(v) * b.eval(v); }
                else if (take('/')) { final Node a = left, b = parseUnary(); left = v -> a.eval(v) / b.eval(v); }
                else if (take('%')) { final Node a = left, b = parseUnary(); left = v -> a.eval(v) % b.eval(v); }
                else return left;
            }
        }

        private Node parseUnary() {
            skip();
            if (take('+')) return parseUnary();
            if (take('-')) { final Node n = parseUnary(); return v -> -n.eval(v); }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skip();
            if (take('(')) {
                Node n = parseExpression();
                skip();
                if (!take(')')) throw new IllegalArgumentException("缺少 )");
                return n;
            }
            if (i < s.length() && Character.isDigit(s.charAt(i))) return number();
            if (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
                String name = ident();
                skip();
                if (take('(')) {
                    List<Node> args = new ArrayList<>();
                    skip();
                    if (!take(')')) {
                        do {
                            args.add(parseExpression());
                            skip();
                        } while (take(','));
                        if (!take(')')) throw new IllegalArgumentException("缺少 )");
                    }
                    return function(name, args);
                }
                return variable(name);
            }
            throw new IllegalArgumentException("无法解析表达式: " + s);
        }

        private Node number() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            final double value = Double.parseDouble(s.substring(start, i));
            return v -> value;
        }

        private String ident() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            return s.substring(start, i);
        }

        private Node variable(String name) {
            switch (name) {
                case "p": return v -> v.p;
                case "t": return v -> v.t;
                case "duration": return v -> v.duration;
                case "speed": return v -> v.speed;
                case "width": return v -> v.width;
                case "textWidth": return v -> v.textWidth;
                case "padding": return v -> v.padding;
                case "chars": return v -> v.chars;
                case "pi": return v -> Math.PI;
                default: throw new IllegalArgumentException("未知变量: " + name);
            }
        }

        private Node function(String name, List<Node> a) {
            switch (name) {
                case "sin": one(name, a); return v -> Math.sin(a.get(0).eval(v));
                case "cos": one(name, a); return v -> Math.cos(a.get(0).eval(v));
                case "sqrt": one(name, a); return v -> Math.sqrt(a.get(0).eval(v));
                case "abs": one(name, a); return v -> Math.abs(a.get(0).eval(v));
                case "clamp":
                    three(name, a);
                    return v -> Math.max(a.get(1).eval(v), Math.min(a.get(2).eval(v), a.get(0).eval(v)));
                case "min":
                    two(name, a); return v -> Math.min(a.get(0).eval(v), a.get(1).eval(v));
                case "max":
                    two(name, a); return v -> Math.max(a.get(0).eval(v), a.get(1).eval(v));
                case "pow":
                    two(name, a); return v -> Math.pow(a.get(0).eval(v), a.get(1).eval(v));
                case "lerp":
                    three(name, a);
                    return v -> {
                        double t = a.get(2).eval(v);
                        return a.get(0).eval(v) * (1 - t) + a.get(1).eval(v) * t;
                    };
                case "smoothstep":
                    three(name, a);
                    return v -> {
                        double edge0 = a.get(0).eval(v), edge1 = a.get(1).eval(v);
                        double x = a.get(2).eval(v);
                        double t = edge1 == edge0 ? 0 : (x - edge0) / (edge1 - edge0);
                        t = Math.max(0, Math.min(1, t));
                        return t * t * (3 - 2 * t);
                    };
                default: throw new IllegalArgumentException("未知函数: " + name);
            }
        }

        private void one(String n, List<Node> a) { if (a.size() != 1) throw new IllegalArgumentException(n + " 需要 1 个参数"); }
        private void two(String n, List<Node> a) { if (a.size() != 2) throw new IllegalArgumentException(n + " 需要 2 个参数"); }
        private void three(String n, List<Node> a) { if (a.size() != 3) throw new IllegalArgumentException(n + " 需要 3 个参数"); }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        boolean take(char c) { skip(); if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
    }
}
