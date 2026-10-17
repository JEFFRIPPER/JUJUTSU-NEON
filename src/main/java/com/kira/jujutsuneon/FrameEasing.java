package com.kira.jujutsuneon;

import java.util.Locale;

/**
 * Кривые перехода между ключами (для формата с ключами — при загрузке они запекаются в каждый кадр).
 *
 * Имена: linear, step (ступенька — держать до следующего ключа), smooth (smoothstep), smoother,
 * in/out/inout + sine, quad, cubic, quart, quint, expo, circ, back, elastic, bounce
 * (например "outquad", "inoutsine", "outback"), а также bezier: [x1, y1, x2, y2] как в CSS.
 * Принимаются и имена Player Animator: "EASEINOUTSINE" и т.п.
 */
final class FrameEasing {

    interface Fn {
        double apply(double t);
    }

    static final Fn LINEAR = t -> t;
    static final Fn STEP = t -> t >= 1.0 ? 1.0 : 0.0;

    private FrameEasing() {
    }

    static Fn byName(String name) {
        if (name == null) return LINEAR;
        String n = name.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (n.startsWith("ease")) n = n.substring(4);
        switch (n) {
            case "", "linear", "constant0" -> {
                return LINEAR;
            }
            case "step", "hold", "constant" -> {
                return STEP;
            }
            case "smooth", "smoothstep" -> {
                return t -> t * t * (3 - 2 * t);
            }
            case "smoother", "smootherstep" -> {
                return t -> t * t * t * (t * (t * 6 - 15) + 10);
            }
            default -> {
            }
        }
        String dir;
        String base;
        if (n.startsWith("inout")) {
            dir = "inout";
            base = n.substring(5);
        } else if (n.startsWith("in")) {
            dir = "in";
            base = n.substring(2);
        } else if (n.startsWith("out")) {
            dir = "out";
            base = n.substring(3);
        } else {
            dir = "inout";
            base = n;
        }
        Fn in = baseIn(base);
        if (in == null) return LINEAR;
        return switch (dir) {
            case "in" -> in;
            case "out" -> t -> 1.0 - in.apply(1.0 - t);
            default -> t -> t < 0.5 ? in.apply(t * 2.0) / 2.0 : 1.0 - in.apply((1.0 - t) * 2.0) / 2.0;
        };
    }

    private static Fn baseIn(String base) {
        return switch (base) {
            case "sine" -> t -> 1.0 - Math.cos(t * Math.PI / 2.0);
            case "quad" -> t -> t * t;
            case "cubic" -> t -> t * t * t;
            case "quart" -> t -> t * t * t * t;
            case "quint" -> t -> t * t * t * t * t;
            case "expo" -> t -> t <= 0.0 ? 0.0 : Math.pow(2.0, 10.0 * t - 10.0);
            case "circ" -> t -> 1.0 - Math.sqrt(Math.max(0.0, 1.0 - t * t));
            case "back" -> t -> 2.70158 * t * t * t - 1.70158 * t * t;
            case "elastic" -> t -> {
                if (t <= 0.0 || t >= 1.0) return t;
                return -Math.pow(2.0, 10.0 * t - 10.0) * Math.sin((t * 10.0 - 10.75) * (2.0 * Math.PI / 3.0));
            };
            case "bounce" -> t -> 1.0 - bounceOut(1.0 - t);
            default -> null;
        };
    }

    private static double bounceOut(double t) {
        double n1 = 7.5625, d1 = 2.75;
        if (t < 1.0 / d1) return n1 * t * t;
        if (t < 2.0 / d1) {
            t -= 1.5 / d1;
            return n1 * t * t + 0.75;
        }
        if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            return n1 * t * t + 0.9375;
        }
        t -= 2.625 / d1;
        return n1 * t * t + 0.984375;
    }

    /** Кубическая Безье с концами (0,0) и (1,1), как cubic-bezier в CSS. */
    static Fn bezier(double x1, double y1, double x2, double y2) {
        return t -> {
            if (t <= 0.0) return 0.0;
            if (t >= 1.0) return 1.0;
            // ищем параметр u, при котором x(u) = t (Ньютон + страховка бисекцией)
            double u = t;
            for (int i = 0; i < 8; i++) {
                double x = bez(u, x1, x2) - t;
                double dx = bezD(u, x1, x2);
                if (Math.abs(x) < 1.0E-6) break;
                if (Math.abs(dx) < 1.0E-6) break;
                u -= x / dx;
            }
            if (u < 0.0 || u > 1.0 || Math.abs(bez(u, x1, x2) - t) > 1.0E-4) {
                double lo = 0.0, hi = 1.0;
                u = t;
                for (int i = 0; i < 30; i++) {
                    double x = bez(u, x1, x2);
                    if (x < t) lo = u;
                    else hi = u;
                    u = (lo + hi) / 2.0;
                }
            }
            return bez(u, y1, y2);
        };
    }

    private static double bez(double u, double p1, double p2) {
        double v = 1.0 - u;
        return 3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u;
    }

    private static double bezD(double u, double p1, double p2) {
        double v = 1.0 - u;
        return 3.0 * v * v * p1 + 6.0 * v * u * (p2 - p1) + 3.0 * u * u * (1.0 - p2);
    }
}
