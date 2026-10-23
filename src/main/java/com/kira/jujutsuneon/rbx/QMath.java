package com.kira.jujutsuneon.rbx;

/**
 * Кватернионы на double[4] = {x, y, z, w}. Порядок поворотов как у GeckoLib (RenderUtils.rotateMatrixAroundBone):
 * R = Rz · Ry · Rx, углы — радианы в соглашении GeoBone (X и Y со знаком минус относительно Blockbench).
 */
public final class QMath {

    private QMath() {
    }

    public static void identity(double[] q) {
        q[0] = q[1] = q[2] = 0;
        q[3] = 1;
    }

    /** q = Rz(z) · Ry(y) · Rx(x). */
    public static void fromEulerZYX(double x, double y, double z, double[] q) {
        double cx = Math.cos(x * 0.5), sx = Math.sin(x * 0.5);
        double cy = Math.cos(y * 0.5), sy = Math.sin(y * 0.5);
        double cz = Math.cos(z * 0.5), sz = Math.sin(z * 0.5);
        q[3] = cz * cy * cx + sz * sy * sx;
        q[0] = cz * cy * sx - sz * sy * cx;
        q[1] = cz * sy * cx + sz * cy * sx;
        q[2] = sz * cy * cx - cz * sy * sx;
    }

    /** Обратно к углам {x, y, z} для R = Rz · Ry · Rx. */
    public static void toEulerZYX(double[] q, double[] out) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double sy = 2 * (w * y - z * x);
        if (sy > 1) sy = 1;
        if (sy < -1) sy = -1;
        out[1] = Math.asin(sy);
        out[0] = Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y));
        out[2] = Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));
    }

    /** out = a · b. out может совпадать с a или b. */
    public static void mul(double[] a, double[] b, double[] out) {
        double ax = a[0], ay = a[1], az = a[2], aw = a[3];
        double bx = b[0], by = b[1], bz = b[2], bw = b[3];
        out[0] = aw * bx + ax * bw + ay * bz - az * by;
        out[1] = aw * by - ax * bz + ay * bw + az * bx;
        out[2] = aw * bz + ax * by - ay * bx + az * bw;
        out[3] = aw * bw - ax * bx - ay * by - az * bz;
    }

    public static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    public static void normalize(double[] q) {
        double n = Math.sqrt(dot(q, q));
        if (n < 1.0E-12) {
            identity(q);
            return;
        }
        q[0] /= n;
        q[1] /= n;
        q[2] /= n;
        q[3] /= n;
    }

    /** Сферическая интерполяция a → b на t, результат в out. */
    public static void slerp(double[] a, double[] b, double t, double[] out) {
        double bx = b[0], by = b[1], bz = b[2], bw = b[3];
        double cos = dot(a, b);
        if (cos < 0) {
            cos = -cos;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        double k0, k1;
        if (cos > 0.9995) {
            k0 = 1 - t;
            k1 = t;
        } else {
            double ang = Math.acos(cos);
            double sin = Math.sin(ang);
            k0 = Math.sin((1 - t) * ang) / sin;
            k1 = Math.sin(t * ang) / sin;
        }
        out[0] = a[0] * k0 + bx * k1;
        out[1] = a[1] * k0 + by * k1;
        out[2] = a[2] * k0 + bz * k1;
        out[3] = a[3] * k0 + bw * k1;
        normalize(out);
    }

    public static void copy(double[] src, double[] dst) {
        System.arraycopy(src, 0, dst, 0, 4);
    }
}
