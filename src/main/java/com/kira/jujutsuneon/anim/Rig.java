package com.kira.jujutsuneon.anim;

/**
 * Скелет игрока: из позы костей (повороты относительно родителя) считает, куда поставить
 * каждую часть ванильной модели — точку вращения (пиксели модели) и углы ModelPart.
 *
 * Пространство модели — как у ModelPart: Y вниз, −Z вперёд, правая сторона игрока — −X.
 * Таз (точка вращения корпуса и ног) — (0, 12, 0); шея — 12 пикселей над тазом.
 */
public final class Rig {

    /** Отступы костей от родителя в нейтральной позе, пиксели модели. */
    public static final double[] WAIST_PIVOT = {0, 12, 0};
    public static final double[] NECK = {0, -12, 0};
    public static final double[] RIGHT_SHOULDER = {-5, -10, 0};
    public static final double[] LEFT_SHOULDER = {5, -10, 0};
    public static final double[] RIGHT_HIP = {-1.9, 12, 0};
    public static final double[] LEFT_HIP = {1.9, 12, 0};
    /** У тонкой модели («Алекс») плечи на полпикселя ниже. */
    public static final double SLIM_SHOULDER_DROP = 0.5;
    /** Таз над ногами в блоках (пространство тела) — вокруг него Player Animator поворачивает всё тело. */
    public static final double HIP_HEIGHT = 0.7;

    /** Итог для одной части ванильной модели. */
    public static final class Part {
        public final double[] pivot = new double[3];
        /** Углы ModelPart (xRot, yRot, zRot), радианы. */
        public final double[] euler = new double[3];
        /** Кватернион той же ориентации (для превью и точных расчётов). */
        public final double[] rot = new double[4];
        public double bend, bendAxis;
    }

    /** Итог для всей модели. */
    public static final class Output {
        /** Части модели по индексу кости (ROOT не используется). */
        public final Part[] parts = new Part[Joint.COUNT];
        /** Всё тело: сдвиг (блоки) и углы, как ждёт Player Animator для "body". */
        public final double[] rootPos = new double[3];
        public final double[] rootEuler = new double[3];
        public final double[] rootRot = new double[4];

        public Output() {
            for (int i = 0; i < parts.length; i++) parts[i] = new Part();
        }
    }

    private final double[] tq = new double[4];
    private final double[] tv = new double[3];

    /** Посчитать части модели по позе. */
    public void solve(Pose pose, boolean slim, Output out) {
        int r = Joint.ROOT.ordinal();
        AnimMath.copy4(pose.rot[r], out.rootRot);
        AnimMath.toEuler(out.rootRot, out.rootEuler);
        out.rootPos[0] = pose.off[r][0];
        out.rootPos[1] = pose.off[r][1];
        out.rootPos[2] = pose.off[r][2];

        int w = Joint.WAIST.ordinal();
        double[] qw = pose.rot[w];
        double wx = WAIST_PIVOT[0] + pose.off[w][0];
        double wy = WAIST_PIVOT[1] + pose.off[w][1];
        double wz = WAIST_PIVOT[2] + pose.off[w][2];

        // Корпус: ModelPart корпуса вращается вокруг шеи — ставим шею туда, куда её привёл наклон от таза.
        Part torso = out.parts[w];
        place(qw, wx, wy, wz, NECK, null, torso.pivot);
        AnimMath.copy4(qw, torso.rot);
        finish(torso, pose, w);

        child(pose, Joint.HEAD, qw, wx, wy, wz, NECK, 0, out);
        double drop = slim ? SLIM_SHOULDER_DROP : 0;
        child(pose, Joint.RIGHT_ARM, qw, wx, wy, wz, RIGHT_SHOULDER, drop, out);
        child(pose, Joint.LEFT_ARM, qw, wx, wy, wz, LEFT_SHOULDER, drop, out);

        leg(pose, Joint.RIGHT_LEG, RIGHT_HIP, out);
        leg(pose, Joint.LEFT_LEG, LEFT_HIP, out);
    }

    private void child(Pose pose, Joint j, double[] qw, double wx, double wy, double wz, double[] rest, double drop, Output out) {
        int k = j.ordinal();
        Part p = out.parts[k];
        tv[0] = rest[0] + pose.off[k][0];
        tv[1] = rest[1] + drop + pose.off[k][1];
        tv[2] = rest[2] + pose.off[k][2];
        AnimMath.rotate(qw, tv, tv);
        p.pivot[0] = wx + tv[0];
        p.pivot[1] = wy + tv[1];
        p.pivot[2] = wz + tv[2];
        AnimMath.mul(qw, pose.rot[k], p.rot);
        finish(p, pose, k);
    }

    private void leg(Pose pose, Joint j, double[] hip, Output out) {
        int k = j.ordinal();
        Part p = out.parts[k];
        p.pivot[0] = hip[0] + pose.off[k][0];
        p.pivot[1] = hip[1] + pose.off[k][1];
        p.pivot[2] = hip[2] + pose.off[k][2];
        AnimMath.copy4(pose.rot[k], p.rot);
        finish(p, pose, k);
    }

    private void place(double[] q, double ox, double oy, double oz, double[] rest, double[] add, double[] out) {
        tv[0] = rest[0] + (add != null ? add[0] : 0);
        tv[1] = rest[1] + (add != null ? add[1] : 0);
        tv[2] = rest[2] + (add != null ? add[2] : 0);
        AnimMath.rotate(q, tv, tv);
        out[0] = ox + tv[0];
        out[1] = oy + tv[1];
        out[2] = oz + tv[2];
    }

    private void finish(Part p, Pose pose, int k) {
        AnimMath.toEuler(p.rot, p.euler);
        p.bend = pose.bend[k];
        p.bendAxis = pose.bendAxis[k];
    }

    // ------------------------------------------------------------------ для превью и эффектов

    /**
     * Точка части (в её локальных пикселях, от точки вращения) в пространстве тела (блоки, Y вверх,
     * −Z вперёд, +X — правая сторона игрока), с учётом поворота всего тела.
     */
    public static void partPointToBody(Output out, Part p, double[] local, double[] res) {
        double[] v = {local[0], local[1], local[2]};
        AnimMath.rotate(p.rot, v, v);
        v[0] += p.pivot[0];
        v[1] += p.pivot[1];
        v[2] += p.pivot[2];
        modelToBody(out, v, res);
    }

    /** Точка в пикселях модели → пространство тела (как рисует PlayerRenderer: масштаб 0,9375, Y вверх). */
    public static void modelToBody(Output out, double[] m, double[] res) {
        double s = 0.9375;
        double bx = -m[0] / 16.0 * s;
        double by = (1.501 - m[1] / 16.0) * s;
        double bz = m[2] / 16.0 * s;
        double[] v = {bx, by - HIP_HEIGHT, bz};
        AnimMath.rotate(out.rootRot, v, v);
        res[0] = v[0] + out.rootPos[0];
        res[1] = v[1] + HIP_HEIGHT + out.rootPos[1];
        res[2] = v[2] + out.rootPos[2];
    }
}
