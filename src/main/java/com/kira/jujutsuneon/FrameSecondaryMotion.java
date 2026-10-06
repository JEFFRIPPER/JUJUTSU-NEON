package com.kira.jujutsuneon;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Живой слой поверх покадровой анимации (включается в файле анимации блоком "secondary"):
 *
 *  - пружины: каждый поворот руки, ноги, корпуса, головы не прыгает в позу кадра, а догоняет её
 *    затухающей пружиной — получаются запаздывание, захлёст на резкой остановке и отдача;
 *  - инерция: ускорение самого игрока (рывок, торможение, прыжок) отклоняет руки и корпус в обратную сторону;
 *  - дыхание: медленный подъём груди и плеч;
 *  - микродвижения: едва заметные живые колебания головы и рук, чтобы поза никогда не была мёртвой.
 *
 * Считается в каждом кадре отрисовки по реальному времени (с подшагами по 1/240 с), не по тикам.
 */
final class FrameSecondaryMotion {

    private final FrameAnimation.Secondary cfg;
    private final float[] x = new float[FrameChannel.COUNT];
    private final float[] v = new float[FrameChannel.COUNT];
    private final boolean[] init = new boolean[FrameChannel.COUNT];
    private Vec3 lastVel;
    private Vec3 accSmooth = Vec3.ZERO;

    FrameSecondaryMotion(FrameAnimation.Secondary cfg) {
        this.cfg = cfg;
    }

    private float groupWeight(FrameChannel c) {
        return switch (c.part) {
            case "rightArm", "leftArm" -> cfg.arms;
            case "rightLeg", "leftLeg" -> cfg.legs;
            case "torso" -> cfg.torso;
            case "head" -> cfg.head;
            case "body" -> cfg.body;
            default -> 0f;
        };
    }

    /**
     * pose — абсолютная поза кадра (изменяется: становится «пружинной»), add — добавки поверх
     * ванили/позы (дыхание, микродвижения, инерция). dt — реальное время с прошлого вызова (с), t — время анимации (с).
     */
    void apply(float[] pose, float[] add, float dt, double t, Entity entity) {
        if (!cfg.enabled || cfg.amount <= 0f) return;
        float amount = cfg.amount;

        // ---- пружины
        if (cfg.stiffness > 0f) {
            float remaining = Math.min(dt, 0.1f);
            for (int c = 0; c < FrameChannel.COUNT; c++) {
                float target = pose[c];
                if (Float.isNaN(target)) {
                    init[c] = false;
                    continue;
                }
                FrameChannel ch = FrameChannel.ALL[c];
                if (ch.kind == FrameChannel.Kind.BEND_AXIS) continue;
                float w = groupWeight(ch) * amount;
                if (w <= 0f) continue;
                if (!init[c]) {
                    x[c] = target;
                    v[c] = 0f;
                    init[c] = true;
                }
                float left = remaining;
                while (left > 1.0E-6f) {
                    float h = Math.min(left, 1f / 240f);
                    float a = cfg.stiffness * (target - x[c]) - cfg.damping * v[c];
                    v[c] += a * h;
                    x[c] += v[c] * h;
                    left -= h;
                }
                pose[c] = target + (x[c] - target) * Math.min(1f, w);
            }
        }

        // ---- инерция от собственного движения
        if (cfg.inertia > 0f && entity != null && dt > 0f) {
            Vec3 vel = entity.getDeltaMovement();
            if (lastVel != null) {
                Vec3 acc = vel.subtract(lastVel).scale(1.0 / Math.max(dt, 1.0E-3));
                accSmooth = accSmooth.lerp(acc, Math.min(1.0, dt * 12.0));
            }
            lastVel = vel;
            double yaw = Math.toRadians(entity.getYRot());
            double fwd = -accSmooth.x * Math.sin(yaw) + accSmooth.z * Math.cos(yaw);
            double side = -accSmooth.x * Math.cos(yaw) - accSmooth.z * Math.sin(yaw);
            double up = accSmooth.y;
            float k = cfg.inertia * amount;
            float armBack = (float) clamp(fwd * 0.9, -0.6, 0.6) * k;   // разгон вперёд — руки отстают назад
            float lean = (float) clamp(-fwd * 0.25, -0.2, 0.2) * k;
            float sway = (float) clamp(side * 0.35, -0.35, 0.35) * k;
            float lift = (float) clamp(-up * 0.4, -0.5, 0.5) * k;
            add[FrameChannel.RIGHT_ARM_PITCH.ordinal()] += armBack;
            add[FrameChannel.LEFT_ARM_PITCH.ordinal()] += armBack;
            add[FrameChannel.RIGHT_ARM_ROLL.ordinal()] += sway + Math.max(0f, lift);
            add[FrameChannel.LEFT_ARM_ROLL.ordinal()] += sway - Math.max(0f, lift);
            add[FrameChannel.TORSO_BEND.ordinal()] += lean;
            add[FrameChannel.HEAD_PITCH.ordinal()] += -lean * 0.5f;
        }

        // ---- дыхание
        if (cfg.breathing > 0f && cfg.breathPeriod > 0.1f) {
            float b = cfg.breathing * amount;
            float s = (float) Math.sin(t * Math.PI * 2.0 / cfg.breathPeriod);
            float deg = (float) Math.PI / 180f;
            add[FrameChannel.TORSO_BEND.ordinal()] += -1.4f * deg * s * b;
            add[FrameChannel.HEAD_PITCH.ordinal()] += 0.8f * deg * s * b;
            add[FrameChannel.RIGHT_ARM_ROLL.ordinal()] += 1.1f * deg * (0.5f + 0.5f * s) * b;
            add[FrameChannel.LEFT_ARM_ROLL.ordinal()] += -1.1f * deg * (0.5f + 0.5f * s) * b;
            add[FrameChannel.RIGHT_ARM_Y.ordinal()] += -0.18f * s * b;
            add[FrameChannel.LEFT_ARM_Y.ordinal()] += -0.18f * s * b;
        }

        // ---- микродвижения (несоизмеримые частоты — не повторяются)
        if (cfg.micro > 0f) {
            float m = cfg.micro * amount * (float) Math.PI / 180f;
            add[FrameChannel.HEAD_YAW.ordinal()] += m * 1.3f * (float) (Math.sin(t * 0.83) * 0.6 + Math.sin(t * 2.17 + 1.3) * 0.4);
            add[FrameChannel.HEAD_PITCH.ordinal()] += m * 0.9f * (float) (Math.sin(t * 1.11 + 0.7) * 0.6 + Math.sin(t * 2.71) * 0.4);
            add[FrameChannel.RIGHT_ARM_PITCH.ordinal()] += m * 0.8f * (float) Math.sin(t * 1.37 + 2.1);
            add[FrameChannel.LEFT_ARM_PITCH.ordinal()] += m * 0.8f * (float) Math.sin(t * 1.29 + 0.4);
            add[FrameChannel.TORSO_BEND.ordinal()] += m * 0.4f * (float) Math.sin(t * 0.61 + 1.9);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
