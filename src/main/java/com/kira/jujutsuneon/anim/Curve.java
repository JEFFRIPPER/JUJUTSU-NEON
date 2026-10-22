package com.kira.jujutsuneon.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Кривая одного канала: ключи (время в секундах, значение) и способ перехода в каждый ключ.
 *
 * По умолчанию переход SMOOTH — кубическая кривая Эрмита с «автоматическими зажатыми»
 * касательными, как в Blender (Auto Clamped): движение проходит через ключ без остановки,
 * если значение продолжает расти или падать, и мягко тормозит только в крайних точках
 * (там, где движение разворачивается). Нет «стоп-кадра» на каждом ключе и нет перелёта
 * за значение ключа. Скорость непрерывна — нет рывков ни на каком FPS.
 */
public final class Curve {

    public enum Mode {
        /** Плавно, без остановки в ключе (по умолчанию). */
        SMOOTH,
        /** По прямой. */
        LINEAR,
        /** Держать прошлое значение и перескочить в ключ. */
        STEP,
        /** Медленный старт и медленный финиш (останавливается в обоих ключах). */
        EASE,
        /** Медленный старт, разгон к ключу (замах → бросок). */
        IN,
        /** Быстрый старт, торможение в ключ (удар в цель). */
        OUT;

        public static Mode parse(String s) {
            if (s == null) return SMOOTH;
            String n = s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (n.startsWith("ease")) n = n.substring(4);
            return switch (n) {
                case "", "smooth", "auto", "catmull", "bezier", "spline", "inoutsine", "inoutquad", "inoutcubic",
                     "inoutquart", "inoutquint", "inoutexpo", "inoutcirc", "inoutback", "inoutelastic",
                     "inoutbounce" -> SMOOTH;
                case "linear" -> LINEAR;
                case "step", "constant", "hold" -> STEP;
                case "ease", "inout", "smoothstep", "stop" -> EASE;
                case "in", "insine", "inquad", "incubic", "inquart", "inquint", "inexpo", "incirc", "inback",
                     "inelastic", "inbounce" -> IN;
                case "out", "outsine", "outquad", "outcubic", "outquart", "outquint", "outexpo", "outcirc",
                     "outback", "outelastic", "outbounce" -> OUT;
                default -> SMOOTH;
            };
        }
    }

    private final double[] t;
    private final double[] v;
    private final Mode[] mode;
    private final double[] tangent;
    private final boolean loop;
    private final double period;
    private final int n;

    private Curve(double[] t, double[] v, Mode[] mode, boolean loop, double period) {
        this.t = t;
        this.v = v;
        this.mode = mode;
        this.loop = loop;
        this.period = period;
        this.n = t.length;
        this.tangent = new double[n];
        for (int i = 0; i < n; i++) tangent[i] = autoTangent(i);
    }

    public int keyCount() {
        return n;
    }

    public double firstTime() {
        return t[0];
    }

    public double lastTime() {
        return t[n - 1];
    }

    public double valueAtKey(int i) {
        return v[i];
    }

    public double timeAtKey(int i) {
        return t[i];
    }

    private double tk(int i) {
        if (!loop) return t[Math.max(0, Math.min(n - 1, i))];
        int k = Math.floorMod(i, n);
        int lap = Math.floorDiv(i, n);
        return t[k] + lap * period;
    }

    private double vk(int i) {
        if (!loop) return v[Math.max(0, Math.min(n - 1, i))];
        return v[Math.floorMod(i, n)];
    }

    private double autoTangent(int i) {
        if (n < 2) return 0;
        if (!loop && (i == 0 || i == n - 1)) return 0;
        double t0 = tk(i - 1), t1 = tk(i), t2 = tk(i + 1);
        double h0 = t1 - t0, h1 = t2 - t1;
        if (h0 <= 1.0E-9 || h1 <= 1.0E-9) return 0;
        double d0 = (vk(i) - vk(i - 1)) / h0;
        double d1 = (vk(i + 1) - vk(i)) / h1;
        if (d0 * d1 <= 0) return 0; // крайняя точка — мягкий разворот
        double m = (h1 * d0 + h0 * d1) / (h0 + h1);
        double lim = 3.0 * Math.min(Math.abs(d0), Math.abs(d1));
        if (Math.abs(m) > lim) m = Math.signum(m) * lim;
        return m;
    }

    public double sample(double time) {
        if (n == 1) return v[0];
        int i;
        double x = time;
        if (loop) {
            x = time - Math.floor((time - t[0]) / period) * period; // x ∈ [t0, t0 + period)
            i = n - 1;
            for (int k = 0; k < n - 1; k++) {
                if (x < t[k + 1]) {
                    i = k;
                    break;
                }
            }
        } else {
            if (x <= t[0]) return v[0];
            if (x >= t[n - 1]) return v[n - 1];
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (t[mid] <= x) lo = mid;
                else hi = mid;
            }
            i = lo;
        }
        double ta = tk(i), tb = tk(i + 1);
        double va = vk(i), vb = vk(i + 1);
        double h = tb - ta;
        if (h <= 1.0E-9) return vb;
        double s = (x - ta) / h;
        Mode m = mode[Math.floorMod(i + 1, n)];
        switch (m) {
            case STEP:
                return va;
            case LINEAR:
                return va + (vb - va) * s;
            default:
                break;
        }
        double ma = tangent[Math.floorMod(i, n)];
        double mb = tangent[Math.floorMod(i + 1, n)];
        if (m == Mode.EASE) {
            ma = 0;
            mb = 0;
        } else if (m == Mode.IN) {
            ma = 0;
        } else if (m == Mode.OUT) {
            mb = 0;
        }
        double s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1;
        double h10 = s3 - 2 * s2 + s;
        double h01 = -2 * s3 + 3 * s2;
        double h11 = s3 - s2;
        return h00 * va + h10 * h * ma + h01 * vb + h11 * h * mb;
    }

    // ------------------------------------------------------------------ сборка

    public static final class Builder {
        private final List<double[]> keys = new ArrayList<>();
        private final List<Mode> modes = new ArrayList<>();

        public Builder key(double time, double value, Mode mode) {
            // ключ в то же время заменяет прежний
            for (int i = 0; i < keys.size(); i++) {
                if (Math.abs(keys.get(i)[0] - time) < 1.0E-7) {
                    keys.set(i, new double[]{time, value});
                    modes.set(i, mode);
                    return this;
                }
            }
            keys.add(new double[]{time, value});
            modes.add(mode);
            return this;
        }

        public boolean isEmpty() {
            return keys.isEmpty();
        }

        /**
         * @param loop   петля: кривая повторяется с периодом period, касательные считаются через стык
         * @param period длина петли в секундах
         */
        public Curve build(boolean loop, double period) {
            if (keys.isEmpty()) return null;
            Integer[] idx = new Integer[keys.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(keys.get(a)[0], keys.get(b)[0]));
            List<double[]> k = new ArrayList<>();
            List<Mode> m = new ArrayList<>();
            for (Integer i : idx) {
                k.add(keys.get(i));
                m.add(modes.get(i));
            }
            boolean lp = loop && period > 1.0E-6 && k.size() > 1;
            if (lp) {
                // ключ на стыке (t = t0 + period) дублирует первый — убираем, петля замкнётся сама
                double t0 = k.get(0)[0];
                while (k.size() > 1 && k.get(k.size() - 1)[0] >= t0 + period - 1.0E-6) {
                    k.remove(k.size() - 1);
                    m.remove(m.size() - 1);
                }
                lp = k.size() > 1;
            }
            double[] tt = new double[k.size()];
            double[] vv = new double[k.size()];
            Mode[] mm = new Mode[k.size()];
            for (int i = 0; i < tt.length; i++) {
                tt[i] = k.get(i)[0];
                vv[i] = k.get(i)[1];
                mm[i] = m.get(i);
            }
            return new Curve(tt, vv, mm, lp, period);
        }
    }
}
