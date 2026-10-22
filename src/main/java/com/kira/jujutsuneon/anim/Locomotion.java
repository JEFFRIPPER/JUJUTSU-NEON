package com.kira.jujutsuneon.anim;

import java.util.function.Function;

/**
 * Базовый слой: движение игрока в повязке (как «живые» движения Epic Fight).
 *
 * Состояния не переключаются рывком — у каждого свой вес, веса плавно идут к цели:
 * стоит → идёт → бежит → сверхбег (по скорости), присед, воздух (поза зависит от скорости
 * по вертикали: толчок → верхняя точка → падение), приземление (добавочная «просадка»),
 * зарядка прыжка, взлёт, полёт, дэши. Циклы шагов идут по пройденному пути, поэтому ноги
 * не «скользят» и ускоряются вместе с игроком; все циклы шагов синхронны по фазе
 * (правая нога впереди на фазе 0, левая — на 0,5), поэтому смешиваются без каши.
 */
public final class Locomotion {

    /** Входные данные кадра (заполняет клиент или превью). */
    public static final class Input {
        /** Повязка надета и ничто не требует ванильной позы (лодка, сон, плавание, элитры…). */
        public boolean active;
        /** Время кадра, секунды (сколько прошло с прошлого кадра). */
        public double dt;
        /** Скорость по горизонтали, блоки/с. */
        public double speed;
        /** Движется ли игрок спиной вперёд. */
        public boolean backwards;
        /** Скорость по вертикали, блоки/с (+вверх). */
        public double vy;
        public boolean onGround;
        public boolean sneaking;
        /** Поворот тела, градусы/с (+вправо). */
        public double turnRate;
        /** Разгон вперёд, блоки/с². */
        public double accel;

        /** Зарядка прыжка: вес (0..1) и время с начала, секунды. */
        public double chargeWeight, chargeAge;
        /** Взлёт после заряженного прыжка: время, секунды (−1 — нет). */
        public double leapAge = -1;

        /** Полёт: веса (0..1) полёта, ускорения, посадки и движения; угол скорости к вертикали (90 — горизонтально), крен (−1..1). */
        public double flightW, boostW, landW, flightMove, elevation = 90, bank;

        /** Дэш: вид (−1 нет, 0 вперёд, 1 назад, 2 влево, 3 вправо) и время с начала, тики (с долей тика). */
        public int dashKind = -1;
        public double dashAge;

        /** Руки заняты ванилью (удар, щит, лук, еда): эта рука остаётся ванильной. */
        public boolean rightArmVanilla, leftArmVanilla;
    }

    public static final int FRONT_DASH_TICKS = 14;
    public static final int SIDE_DASH_TICKS = 4;

    private final Function<String, Clip> clips;
    private final Pose acc = new Pose(), tmp = new Pose(), tmp2 = new Pose();
    private final double[] tq = new double[4];

    private double phase;
    private double wIdle = 1, wWalk, wRun, wSprint, wAir, wSneak, wMoveSneak;
    private double landT = -1, landStrength, fallSpeed;
    private boolean wasOnGround = true;
    private double leanRoll, leanPitch;
    private boolean initialized;

    /** Итог для взгляда (см. {@link Animator#applyLook}). */
    public double look = 1, follow = 1, rootComp;

    public Locomotion(Function<String, Clip> clips) {
        this.clips = clips;
    }

    public double phase() {
        return phase;
    }

    private Clip clip(String name) {
        return clips.apply(name);
    }

    /** Каденс: сколько полных циклов шагов в секунду при скорости v (блоки/с). */
    static double cadence(double v) {
        return AnimMath.clamp(0.75 * Math.pow(Math.max(0, v), 0.45), 0.55, 3.1);
    }

    private static double tri(double v, double a, double b, double c, double d) {
        if (v <= a || v >= d) return 0;
        if (v < b) return (v - a) / (b - a);
        if (v <= c) return 1;
        return (d - v) / (d - c);
    }

    public void evaluate(Input in, Pose out) {
        if (!in.active) {
            out.setVanilla();
            look = 0;
            follow = 0;
            rootComp = 0;
            initialized = false;
            return;
        }
        double dt = AnimMath.clamp(in.dt, 0, 0.25);
        double v = in.speed;
        idleClock += dt;
        if (idleClock > 3600) idleClock -= 3600;

        // ---- веса состояний
        boolean air = !in.onGround && in.flightW < 0.5;
        double tIdle = 1 - AnimMath.smooth((v - 0.15) / 0.9);
        double tWalk = tri(v, 0.15, 1.2, 2.4, 4.6);
        double tRun = tri(v, 2.4, 4.6, 9.5, 15);
        double tSprint = AnimMath.smooth((v - 9.5) / 6.5);
        double sum = tIdle + tWalk + tRun + tSprint;
        if (sum < 1.0E-6) {
            tIdle = 1;
            sum = 1;
        }
        tIdle /= sum;
        tWalk /= sum;
        tRun /= sum;
        tSprint /= sum;
        if (!initialized) {
            wIdle = tIdle;
            wWalk = tWalk;
            wRun = tRun;
            wSprint = tSprint;
            wAir = air ? 1 : 0;
            wSneak = in.sneaking ? 1 : 0;
            wasOnGround = in.onGround;
            initialized = true;
        }
        double rate = 9;
        wIdle = AnimMath.approach(wIdle, tIdle, rate, dt);
        wWalk = AnimMath.approach(wWalk, tWalk, rate, dt);
        wRun = AnimMath.approach(wRun, tRun, rate, dt);
        wSprint = AnimMath.approach(wSprint, tSprint, rate, dt);
        wAir = AnimMath.approach(wAir, air ? 1 : 0, air ? 10 : 22, dt);
        wSneak = AnimMath.approach(wSneak, in.sneaking ? 1 : 0, 12, dt);
        wMoveSneak = AnimMath.approach(wMoveSneak, AnimMath.smooth((v - 0.1) / 0.9), 10, dt);

        // ---- фаза шагов: по пройденному пути
        double cad = cadence(v) * AnimMath.smooth(v / 0.6);
        phase += dt * cad * (in.backwards ? -1 : 1);
        phase -= Math.floor(phase);

        // ---- приземление
        if (!in.onGround) fallSpeed = Math.max(fallSpeed, -in.vy);
        if (in.onGround && !wasOnGround && in.flightW < 0.3) {
            double s = AnimMath.clamp((fallSpeed - 3) / 18, 0, 1);
            if (s > 0.02) {
                landT = 0;
                landStrength = 0.35 + 0.65 * s;
            }
            fallSpeed = 0;
        }
        if (in.onGround) fallSpeed = 0;
        wasOnGround = in.onGround;

        double rc = 0;

        // ---- земля: смесь циклов
        out.reset();
        double accW = 0;
        accW = mixCycle(out, "loco_idle", wIdle, accW, true);
        accW = mixCycle(out, "loco_walk", wWalk, accW, false);
        accW = mixCycle(out, "loco_run", wRun, accW, false);
        mixCycle(out, "loco_sprint", wSprint, accW, false);

        if (wSneak > 1.0E-3) {
            Clip si = clip("loco_sneak_idle"), sw = clip("loco_sneak_walk");
            if (si != null) {
                sampleCycle(si, true, tmp);
                if (sw != null) {
                    sampleCycle(sw, false, tmp2);
                    tmp.blendTowards(tmp2, wMoveSneak);
                }
                out.blendTowards(tmp, wSneak);
            }
        }

        // ---- приземление (добавочно)
        if (landT >= 0) {
            Clip l = clip("loco_land");
            landT += dt;
            if (l == null || landT > l.length) {
                landT = -1;
            } else {
                l.sample(landT, false, tmp, tq);
                for (int j = 0; j < Joint.COUNT; j++) out.addJoint(tmp, j, landStrength * tmp.weight[j], tq);
            }
        }

        // ---- зарядка прыжка
        if (in.chargeWeight > 1.0E-3) {
            Clip c = clip("loco_charge");
            if (c != null) {
                c.sample(Math.min(in.chargeAge, c.length), false, tmp, tq);
                out.blendTowards(tmp, in.chargeWeight);
            }
        }

        // ---- наклоны от разгона и поворотов (на земле)
        double groundish = (1 - wAir) * (1 - in.flightW);
        double moving = AnimMath.smooth(v / 3.0);
        double tRoll = -AnimMath.clamp(in.turnRate / 220.0, -1, 1) * 9.0 * moving * groundish;
        double tPitch = -AnimMath.clamp(in.accel / 40.0, -1, 1) * 6.0 * groundish;
        leanRoll = AnimMath.approach(leanRoll, tRoll, 7, dt);
        leanPitch = AnimMath.approach(leanPitch, tPitch, 7, dt);
        if (Math.abs(leanRoll) > 0.01 || Math.abs(leanPitch) > 0.01) {
            AnimMath.fromEuler(leanPitch * AnimMath.DEG, 0, leanRoll * AnimMath.DEG, tq);
            int r = Joint.ROOT.ordinal();
            AnimMath.mul(tq, out.rot[r], out.rot[r]);
        }

        // ---- воздух: поза по вертикальной скорости
        if (wAir > 1.0E-3) {
            Clip a = clip("loco_air");
            if (a != null) {
                double s = AnimMath.clamp(0.5 - in.vy / 16.0, 0, 1);
                a.sample(s * a.length, false, tmp, tq);
                out.blendTowards(tmp, wAir);
            }
        }

        // ---- взлёт
        if (in.leapAge >= 0) {
            Clip lp = clip("loco_leap");
            double a = in.leapAge * 20; // тики
            double outW = Math.max(AnimMath.smooth((a - 14) / 10), in.flightW > 0.5 ? AnimMath.smooth((a - 8) / 6) : 0);
            double w = AnimMath.smooth(a / 1.5) * (1 - outW);
            if (lp != null && w > 1.0E-3) {
                lp.sample(in.leapAge, false, tmp, tq);
                out.blendTowards(tmp, w);
            }
        }

        // ---- полёт
        if (in.flightW > 1.0E-3) {
            flightPose(in, tmp);
            out.blendTowards(tmp, in.flightW);
            rc = Math.max(rc, 0.8 * in.flightW);
        }

        // ---- дэш
        double dashW = 0;
        if (in.dashKind >= 0) {
            dashW = dashPose(in, tmp);
            if (dashW > 1.0E-3) out.blendTowards(tmp, dashW);
            rc = Math.max(rc, 0.75 * dashW);
        }

        // ---- ноги на земле: таз опускается ровно настолько, чтобы нижняя стопа касалась земли
        //      (сгиб колен, широкий шаг, наклон — всё само даёт правильную высоту; y клипа — «подлёт»)
        double gw = groundish * (in.leapAge >= 0 ? 1 - AnimMath.smooth(in.leapAge * 20 / 2.0) : 1);
        if (gw > 1.0E-3) {
            int r = Joint.ROOT.ordinal();
            double lift = out.off[r][1];
            out.off[r][1] = 0;
            double minY = lowestFoot(out);
            out.off[r][1] = lift - minY * gw;
        }

        if (in.rightArmVanilla) out.vanilla[Joint.RIGHT_ARM.ordinal()] = 1;
        if (in.leftArmVanilla) out.vanilla[Joint.LEFT_ARM.ordinal()] = 1;
        for (int j = 0; j < Joint.COUNT; j++) out.weight[j] = 1;

        look = 1;
        follow = (1 - dashW) * (1 - in.boostW * in.flightW);
        rootComp = rc;
    }

    private double mixCycle(Pose out, String name, double w, double accW, boolean idle) {
        if (w <= 1.0E-4) return accW;
        Clip c = clip(name);
        if (c == null) return accW;
        sampleCycle(c, idle, tmp);
        double total = accW + w;
        if (accW <= 1.0E-6) out.copyFrom(tmp);
        else out.blendTowards(tmp, w / total);
        return total;
    }

    private final Rig rig = new Rig();
    private final Rig.Output rigOut = new Rig.Output();
    private final double[] footLocal = new double[3], footBody = new double[3], bendQ = new double[4];

    /** Высота самой низкой точки подошвы над землёй (блоки), если смещение всего тела по высоте — 0. */
    double lowestFoot(Pose p) {
        rig.solve(p, false, rigOut);
        double min = Double.MAX_VALUE;
        for (Joint leg : new Joint[]{Joint.RIGHT_LEG, Joint.LEFT_LEG}) {
            Rig.Part part = rigOut.parts[leg.ordinal()];
            double ax = Math.cos(-part.bendAxis), az = Math.sin(-part.bendAxis), sh = Math.sin(part.bend / 2);
            bendQ[0] = ax * sh;
            bendQ[1] = 0;
            bendQ[2] = az * sh;
            bendQ[3] = Math.cos(part.bend / 2);
            for (int cx = -1; cx <= 1; cx += 2) {
                for (int cz = -1; cz <= 1; cz += 2) {
                    // угол подошвы, повёрнутый сгибом колена вокруг середины ноги
                    footLocal[0] = 2 * cx;
                    footLocal[1] = 6;
                    footLocal[2] = 2 * cz;
                    AnimMath.rotate(bendQ, footLocal, footLocal);
                    footLocal[1] += 6;
                    Rig.partPointToBody(rigOut, part, footLocal, footBody);
                    min = Math.min(min, footBody[1]);
                }
            }
        }
        return min;
    }

    /** Цикл шагов — по фазе; цикл покоя (дыхание) — по своим часам. */
    private void sampleCycle(Clip c, boolean idle, Pose dst) {
        double t = idle ? idleClock : phase * c.length;
        c.sample(t, false, dst, tq);
        for (int j = 0; j < Joint.COUNT; j++) dst.weight[j] = 1;
    }

    /** Часы дыхания и покачиваний в полёте. */
    private double idleClock;

    // ------------------------------------------------------------------ полёт

    private void flightPose(Input in, Pose p) {
        Clip hover = clip("fly_hover"), glide = clip("fly_glide"), boost = clip("fly_boost"), land = clip("fly_land");
        p.reset();
        if (hover != null) hover.sample(idleClock % hover.length, false, p, tq);
        double move = in.flightMove;
        double b = in.boostW * move;
        if (glide != null) {
            glide.sample(idleClock % glide.length, false, tmp2, tq);
            applyFlightRoot(tmp2, AnimMath.clamp((in.elevation - 90.0) * 0.18, -26, 4), -in.bank * 14);
            p.blendTowards(tmp2, move * (1 - b));
        }
        if (boost != null) {
            boost.sample(idleClock % boost.length, false, tmp2, tq);
            applyFlightRoot(tmp2, AnimMath.clamp(in.elevation - 90.0, -165, -8), -in.bank * 24);
            p.blendTowards(tmp2, b);
        }
        if (land != null) {
            land.sample(idleClock % land.length, false, tmp2, tq);
            p.blendTowards(tmp2, in.landW);
        }
        for (int j = 0; j < Joint.COUNT; j++) p.weight[j] = 1;
    }

    /** Наклон всего тела по направлению полёта (pitch: + назад; roll: + влево), градусы. */
    private void applyFlightRoot(Pose p, double pitch, double roll) {
        AnimMath.fromEuler(pitch * AnimMath.DEG, 0, roll * AnimMath.DEG, tq);
        int r = Joint.ROOT.ordinal();
        AnimMath.mul(tq, p.rot[r], p.rot[r]);
    }

    // ------------------------------------------------------------------ дэши

    private static final class Raw {
        double bodyY, bodyPitch, bodyYaw, bodyRoll, waist, headPitch, headRoll;
        final double[] rArm = new double[4], lArm = new double[4], rLeg = new double[4], lLeg = new double[4];

        Raw set(double[] part, double pitch, double yaw, double roll, double bend) {
            part[0] = pitch;
            part[1] = yaw;
            part[2] = roll;
            part[3] = bend;
            return this;
        }

        void lerpTo(Raw o, double k) {
            bodyY += (o.bodyY - bodyY) * k;
            bodyPitch += (o.bodyPitch - bodyPitch) * k;
            bodyYaw += (o.bodyYaw - bodyYaw) * k;
            bodyRoll += (o.bodyRoll - bodyRoll) * k;
            waist += (o.waist - waist) * k;
            headPitch += (o.headPitch - headPitch) * k;
            headRoll += (o.headRoll - headRoll) * k;
            for (int i = 0; i < 4; i++) {
                rArm[i] += (o.rArm[i] - rArm[i]) * k;
                lArm[i] += (o.lArm[i] - lArm[i]) * k;
                rLeg[i] += (o.rLeg[i] - rLeg[i]) * k;
                lLeg[i] += (o.lLeg[i] - lLeg[i]) * k;
            }
        }

        void toPose(Pose p) {
            p.reset();
            int r = Joint.ROOT.ordinal();
            p.setEulerDeg(Joint.ROOT, bodyPitch, bodyYaw, bodyRoll);
            p.off[r][1] = 0; // высоту даёт «ноги на земле»
            p.setEulerDeg(Joint.WAIST, waist, 0, 0);
            p.setEulerDeg(Joint.HEAD, headPitch, 0, headRoll);
            limb(p, Joint.RIGHT_ARM, rArm);
            limb(p, Joint.LEFT_ARM, lArm);
            limb(p, Joint.RIGHT_LEG, rLeg);
            limb(p, Joint.LEFT_LEG, lLeg);
        }

        private static void limb(Pose p, Joint j, double[] v) {
            p.setEulerDeg(j, v[0], v[1], v[2]);
            p.bend[j.ordinal()] = v[3] * AnimMath.DEG;
        }
    }

    /** Поза дэша и её вес. */
    private double dashPose(Input in, Pose out) {
        double a = in.dashAge;
        boolean side = in.dashKind == 2 || in.dashKind == 3;
        Raw p = new Raw();
        double w;
        if (side) {
            double sg = in.dashKind == 2 ? 1 : -1;
            w = AnimMath.smooth(a / 1.2) * (1 - AnimMath.smooth((a - SIDE_DASH_TICKS - 1) / 8.0));
            // Рывок вбок: тело заваливается в сторону рывка, дальняя нога и рука отстают и развеваются.
            p.bodyRoll = 34 * sg;
            p.bodyYaw = -8 * sg;
            p.bodyPitch = -6;
            p.bodyY = -0.1;
            p.waist = 8;
            p.headRoll = -22 * sg;
            p.headPitch = -4;
            if (sg > 0) {
                p.set(p.rLeg, 6, 0, 28, 18).set(p.lLeg, -16, 0, -6, 34);
                p.set(p.rArm, -26, 0, 58, -24).set(p.lArm, 22, 0, -16, -30);
            } else {
                p.set(p.lLeg, 6, 0, -28, 18).set(p.rLeg, -16, 0, 6, 34);
                p.set(p.lArm, -26, 0, -58, -24).set(p.rArm, 22, 0, 16, -30);
            }
            p.toPose(out);
            return w;
        }
        w = AnimMath.smooth(a / 2.0) * (1 - AnimMath.smooth((a - FRONT_DASH_TICKS) / 4.0));
        double brake = AnimMath.smooth((a - (FRONT_DASH_TICKS - 5)) / 3.0);
        Raw drive = new Raw();
        if (in.dashKind == 0) {
            // Рывок вперёд: всё тело наклонено, быстрые шаги, руки работают.
            double ph = a * 1.35 * Math.PI * 0.5;
            double sn = Math.sin(ph);
            drive.bodyPitch = -30;
            drive.bodyY = -0.06;
            drive.waist = 12;
            drive.headPitch = -6;
            drive.set(drive.rLeg, -14 + 50 * sn, 0, 3, 24 + 34 * Math.max(0, -sn));
            drive.set(drive.lLeg, -14 - 50 * sn, 0, -3, 24 + 34 * Math.max(0, sn));
            drive.set(drive.rArm, 30 - 46 * sn, 0, 12, -70);
            drive.set(drive.lArm, 30 + 46 * sn, 0, -12, -70);
        } else {
            // Рывок назад: низкая стойка, скольжение спиной вперёд, руки вперёд для равновесия.
            drive.bodyPitch = -12;
            drive.bodyY = -0.16;
            drive.waist = 14;
            drive.headPitch = -6;
            drive.set(drive.rLeg, -36, 0, 6, 58).set(drive.lLeg, 22, 0, -6, 38);
            drive.set(drive.rArm, -50, 0, 22, -34).set(drive.lArm, -44, 0, -22, -34);
        }
        Raw stop = new Raw();
        // Торможение: корпус откидывается, передняя нога упирается, руки в стороны.
        stop.bodyPitch = in.dashKind == 0 ? 14 : -10;
        stop.bodyY = -0.16;
        stop.waist = 6;
        stop.headPitch = in.dashKind == 0 ? 4 : -4;
        stop.set(stop.rLeg, -46, 0, 4, 16).set(stop.lLeg, 22, 0, -4, 62);
        stop.set(stop.rArm, -52, 0, 36, -24).set(stop.lArm, -40, 0, -40, -24);
        drive.lerpTo(stop, brake);
        drive.toPose(out);
        return w;
    }
}
