package com.kira.jujutsuneon.anim;

/**
 * Поза скелета: для каждой кости — поворот (кватернион, относительно родителя), смещение и сгиб.
 *
 * Единицы: смещение ROOT — блоки (пространство тела, Y вверх), остальных костей — пиксели модели
 * (пространство родителя, Y вниз, как у ModelPart). Сгиб и направление сгиба — радианы
 * (сгиб локтя/колена по середине конечности через bendy-lib).
 *
 * {@link #weight} — сколько поза «весит» на кости (маска слоя): 1 — кость задана, 0 — не задана.
 * {@link #vanilla} — какая доля кости остаётся ванильной (поза Minecraft: взгляд, взмах рукой,
 * ходьба без повязки). Итог кости = смесь(наша поза, ванильная, vanilla).
 */
public final class Pose {

    public final double[][] rot = new double[Joint.COUNT][4];
    public final double[][] off = new double[Joint.COUNT][3];
    public final double[] bend = new double[Joint.COUNT];
    public final double[] bendAxis = new double[Joint.COUNT];
    public final double[] weight = new double[Joint.COUNT];
    public final double[] vanilla = new double[Joint.COUNT];

    public Pose() {
        reset();
    }

    /** Нейтральная поза: все кости заданы (вес 1), ничего ванильного. */
    public Pose reset() {
        for (int j = 0; j < Joint.COUNT; j++) resetJoint(j);
        return this;
    }

    public void resetJoint(int j) {
        AnimMath.setIdentity(rot[j]);
        off[j][0] = 0;
        off[j][1] = 0;
        off[j][2] = 0;
        bend[j] = 0;
        bendAxis[j] = 0;
        weight[j] = 1;
        vanilla[j] = 0;
    }

    /** Вся поза — ванильная (как без мода). */
    public Pose setVanilla() {
        for (int j = 0; j < Joint.COUNT; j++) {
            resetJoint(j);
            vanilla[j] = 1;
        }
        return this;
    }

    public Pose copyFrom(Pose o) {
        for (int j = 0; j < Joint.COUNT; j++) copyJoint(o, j);
        return this;
    }

    public void copyJoint(Pose o, int j) {
        AnimMath.copy4(o.rot[j], rot[j]);
        off[j][0] = o.off[j][0];
        off[j][1] = o.off[j][1];
        off[j][2] = o.off[j][2];
        bend[j] = o.bend[j];
        bendAxis[j] = o.bendAxis[j];
        weight[j] = o.weight[j];
        vanilla[j] = o.vanilla[j];
    }

    public void setEulerDeg(Joint j, double pitch, double yaw, double roll) {
        AnimMath.fromEuler(pitch * AnimMath.DEG, yaw * AnimMath.DEG, roll * AnimMath.DEG, rot[j.ordinal()]);
    }

    /** Наложить позу слоя b с силой t (по каждой кости — ещё и с её весом в b). */
    public void blendTowards(Pose b, double t) {
        if (t <= 0) return;
        for (int j = 0; j < Joint.COUNT; j++) {
            double k = t * b.weight[j];
            if (k > 0) mixJoint(b, j, Math.min(1, k));
        }
    }

    /**
     * Кость j := смесь(эта, b, k) — как если бы обе позы смешивались уже с ванильной.
     * Повороты — сферически, смещения и сгибы — линейно.
     */
    public void mixJoint(Pose b, int j, double k) {
        if (k <= 0) return;
        double va = vanilla[j], vb = b.vanilla[j];
        double vn = va + (vb - va) * k;
        double wa = (1 - k) * (1 - va), wb = k * (1 - vb);
        double kk;
        if (wa + wb < 1.0E-9) kk = k;
        else kk = wb / (wa + wb);
        if (kk >= 1) {
            AnimMath.copy4(b.rot[j], rot[j]);
            off[j][0] = b.off[j][0];
            off[j][1] = b.off[j][1];
            off[j][2] = b.off[j][2];
            bend[j] = b.bend[j];
            bendAxis[j] = b.bendAxis[j];
        } else if (kk > 0) {
            AnimMath.slerp(rot[j], b.rot[j], kk, rot[j]);
            off[j][0] += (b.off[j][0] - off[j][0]) * kk;
            off[j][1] += (b.off[j][1] - off[j][1]) * kk;
            off[j][2] += (b.off[j][2] - off[j][2]) * kk;
            bend[j] += (b.bend[j] - bend[j]) * kk;
            bendAxis[j] += AnimMath.wrapRad(b.bendAxis[j] - bendAxis[j]) * kk;
        }
        vanilla[j] = vn;
    }

    /** Добавочная поза (разница от нейтральной) с силой k: rot = rot · add^k, смещения и сгибы прибавляются. */
    public void addJoint(Pose add, int j, double k, double[] tmp) {
        if (k <= 0) return;
        if (k >= 1) AnimMath.copy4(add.rot[j], tmp);
        else {
            AnimMath.setIdentity(tmp);
            AnimMath.slerp(tmp, add.rot[j], k, tmp);
        }
        AnimMath.mul(rot[j], tmp, rot[j]);
        off[j][0] += add.off[j][0] * k;
        off[j][1] += add.off[j][1] * k;
        off[j][2] += add.off[j][2] * k;
        bend[j] += add.bend[j] * k;
    }
}
