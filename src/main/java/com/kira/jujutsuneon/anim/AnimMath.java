package com.kira.jujutsuneon.anim;

/**
 * Математика движка анимаций: кватернионы (x, y, z, w) в массивах double[4] и мелочи.
 *
 * Порядок поворотов везде как у ModelPart и Player Animator: R = Rz · Ry · Rx
 * (к вектору сначала применяется pitch, потом yaw, потом roll).
 * Ядро не зависит от Minecraft — его же собирает инструмент превью (tools/anim-preview).
 */
public final class AnimMath {

    public static final double DEG = Math.PI / 180.0;

    private AnimMath() {
    }

    public static double[] quatIdentity() {
        return new double[]{0, 0, 0, 1};
    }

    public static void setIdentity(double[] q) {
        q[0] = 0;
        q[1] = 0;
        q[2] = 0;
        q[3] = 1;
    }

    /** q = Rz(z) · Ry(y) · Rx(x), углы в радианах. */
    public static void fromEuler(double x, double y, double z, double[] out) {
        double cx = Math.cos(x * 0.5), sx = Math.sin(x * 0.5);
        double cy = Math.cos(y * 0.5), sy = Math.sin(y * 0.5);
        double cz = Math.cos(z * 0.5), sz = Math.sin(z * 0.5);
        // qz * qy
        double ax = -sz * sy, ay = cz * sy, az = sz * cy, aw = cz * cy;
        // (qz * qy) * qx
        out[0] = aw * sx + ax * cx;
        out[1] = ay * cx + az * sx;
        out[2] = az * cx - ay * sx;
        out[3] = aw * cx - ax * sx;
    }

    /** Углы (x, y, z) такие, что Rz(z) · Ry(y) · Rx(x) = q. */
    public static void toEuler(double[] q, double[] out) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double m00 = 1 - 2 * (y * y + z * z);
        double m10 = 2 * (x * y + w * z);
        double m20 = 2 * (x * z - w * y);
        double m21 = 2 * (y * z + w * x);
        double m22 = 1 - 2 * (x * x + y * y);
        double sy = -m20;
        if (sy >= 0.999999) {
            double m01 = 2 * (x * y - w * z);
            double m11 = 1 - 2 * (x * x + z * z);
            out[0] = 0;
            out[1] = Math.PI * 0.5;
            out[2] = Math.atan2(-m01, m11);
            return;
        }
        if (sy <= -0.999999) {
            double m01 = 2 * (x * y - w * z);
            double m11 = 1 - 2 * (x * x + z * z);
            out[0] = 0;
            out[1] = -Math.PI * 0.5;
            out[2] = Math.atan2(-m01, m11);
            return;
        }
        out[0] = Math.atan2(m21, m22);
        out[1] = Math.asin(sy);
        out[2] = Math.atan2(m10, m00);
    }

    /** out = a · b (сначала b, потом a). out может совпадать с a или b. */
    public static void mul(double[] a, double[] b, double[] out) {
        double x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
        double y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
        double z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
        double w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
        out[0] = x;
        out[1] = y;
        out[2] = z;
        out[3] = w;
    }

    public static void conjugate(double[] q, double[] out) {
        out[0] = -q[0];
        out[1] = -q[1];
        out[2] = -q[2];
        out[3] = q[3];
    }

    /** Поворот вектора v кватернионом q (out может совпадать с v). */
    public static void rotate(double[] q, double[] v, double[] out) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double tx = 2 * (y * v[2] - z * v[1]);
        double ty = 2 * (z * v[0] - x * v[2]);
        double tz = 2 * (x * v[1] - y * v[0]);
        double ox = v[0] + w * tx + (y * tz - z * ty);
        double oy = v[1] + w * ty + (z * tx - x * tz);
        double oz = v[2] + w * tz + (x * ty - y * tx);
        out[0] = ox;
        out[1] = oy;
        out[2] = oz;
    }

    /** Сферическая интерполяция по кратчайшему пути. out может совпадать с a или b. */
    public static void slerp(double[] a, double[] b, double t, double[] out) {
        if (t <= 0) {
            copy4(a, out);
            return;
        }
        if (t >= 1) {
            copy4(b, out);
            return;
        }
        double bx = b[0], by = b[1], bz = b[2], bw = b[3];
        double dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw;
        if (dot < 0) {
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        double ka, kb;
        if (dot > 0.9995) {
            ka = 1 - t;
            kb = t;
        } else {
            double th = Math.acos(Math.min(1.0, dot));
            double s = Math.sin(th);
            ka = Math.sin((1 - t) * th) / s;
            kb = Math.sin(t * th) / s;
        }
        out[0] = a[0] * ka + bx * kb;
        out[1] = a[1] * ka + by * kb;
        out[2] = a[2] * ka + bz * kb;
        out[3] = a[3] * ka + bw * kb;
        normalize(out);
    }

    public static void normalize(double[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1.0E-12) {
            setIdentity(q);
            return;
        }
        q[0] /= n;
        q[1] /= n;
        q[2] /= n;
        q[3] /= n;
    }

    public static void copy4(double[] a, double[] out) {
        out[0] = a[0];
        out[1] = a[1];
        out[2] = a[2];
        out[3] = a[3];
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Плавный вход-выход (C¹): 3t² − 2t³. */
    public static double smooth(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    /** Ещё мягче (C²): 6t⁵ − 15t⁴ + 10t³ — для переходов между анимациями. */
    public static double smoother(double t) {
        t = clamp01(t);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** Угол в (−π, π]. */
    public static double wrapRad(double a) {
        a = a % (Math.PI * 2);
        if (a > Math.PI) a -= Math.PI * 2;
        if (a <= -Math.PI) a += Math.PI * 2;
        return a;
    }

    /** Интерполяция углов по кратчайшей дуге (радианы). */
    public static double lerpAngle(double a, double b, double t) {
        return a + wrapRad(b - a) * t;
    }

    /**
     * Сглаживание к цели, не зависящее от частоты кадров: за время dt значение проходит
     * долю 1 − e^(−rate·dt) оставшегося пути.
     */
    public static double approach(double value, double target, double rate, double dt) {
        if (dt <= 0) return value;
        return target + (value - target) * Math.exp(-rate * dt);
    }
}
