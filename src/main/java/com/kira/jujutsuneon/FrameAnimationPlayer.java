package com.kira.jujutsuneon;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Arrays;

/**
 * Проигрыватель покадровой анимации для Player Animator.
 *
 * Поза считается в КАЖДОМ кадре отрисовки (setupAnim вызывается перед каждым рендером модели)
 * по точному времени: игровые тики + доля тика (или реальные часы, если в файле "clock": "real").
 * Между кадрами файла — сглаживание Кэтмелла–Рома, сами кадры проходятся точно.
 */
final class FrameAnimationPlayer implements IAnimation {

    final FrameAnimation anim;
    final int entityId;
    final float speed;
    final boolean mirror;
    private final double startOffset;
    private final Boolean loopOverride;

    private int ticks;
    private final long startNanos = System.nanoTime();
    private long lastSetupNanos = -1L;

    /** Отладка: стоп-кадр (NaN — играет). */
    double frozenFrame = Double.NaN;
    private boolean stopping;
    private double stopAt;
    private float stopFade;

    private double time;
    private double frame;
    private float weight = 1f;
    private final float[] pose = new float[FrameChannel.COUNT];
    private final float[] add = new float[FrameChannel.COUNT];
    private final float[] tmp = new float[FrameChannel.COUNT];
    private final FrameSecondaryMotion secondary;
    private double lastEventAbsFrame = -1.0;

    FrameAnimationPlayer(FrameAnimation anim, int entityId, float speed, boolean mirror, double startFrame, Boolean loopOverride) {
        this.anim = anim;
        this.entityId = entityId;
        this.speed = (speed <= 0f ? 1f : speed) * anim.speed;
        this.mirror = mirror;
        this.startOffset = Math.max(0.0, startFrame) / anim.fps;
        this.loopOverride = loopOverride;
        this.secondary = anim.secondary.enabled ? new FrameSecondaryMotion(anim.secondary) : null;
        this.lastEventAbsFrame = startFrame - 1.0;
        Arrays.fill(pose, Float.NaN);
    }

    private boolean loops() {
        return loopOverride != null ? loopOverride : anim.loop;
    }

    /** Время анимации в секундах в момент tickDelta. */
    double timeAt(float tickDelta) {
        double raw = anim.realTime ? (System.nanoTime() - startNanos) / 1.0E9 : (ticks + tickDelta) / 20.0;
        return startOffset + raw * speed * FrameAnimations.debugSpeed();
    }

    double currentFrame() {
        return frame;
    }

    void stop(float fadeSeconds) {
        if (stopping) return;
        stopping = true;
        stopAt = time;
        stopFade = Math.max(0.0001f, fadeSeconds);
    }

    private double frameFor(double t) {
        if (!Double.isNaN(frozenFrame)) return Math.max(0.0, Math.min(anim.frameCount - 1, frozenFrame));
        if (loops() != anim.loop) {
            double f = t * anim.fps;
            if (loops() && anim.frameCount > 1 && f >= anim.frameCount) {
                double span = anim.frameCount - anim.loopFrom;
                f = anim.loopFrom + ((f - anim.loopFrom) % span);
            } else if (!loops()) {
                f = Math.min(f, anim.frameCount - 1);
            }
            return Math.max(0.0, f);
        }
        return anim.frameAt(t);
    }

    @Override
    public boolean isActive() {
        if (!Double.isNaN(frozenFrame)) return !stopping || time - stopAt < stopFade;
        if (stopping && time - stopAt >= stopFade) return false;
        if (loops() || anim.holdLast) return true;
        return time < anim.duration() + anim.fadeOut + 0.05;
    }

    @Override
    public void tick() {
        ticks++;
        // Время идёт и тогда, когда модель не рисуется (игрок вне экрана) — иначе анимация не закончится.
        time = timeAt(0f);
        // События кадров (по полным тикам): каждое срабатывает ровно один раз, в т.ч. на петлях.
        if (anim.events.isEmpty() || !Double.isNaN(frozenFrame)) return;
        double abs = timeAt(0f) * anim.fps;
        if (abs <= lastEventAbsFrame) return;
        int from = (int) Math.floor(lastEventAbsFrame) + 1;
        int to = (int) Math.floor(abs);
        lastEventAbsFrame = abs;
        for (int n = Math.max(0, from), guard = 0; n <= to && guard < 2000; n++, guard++) {
            int f = n;
            if (loops() && anim.frameCount > 1 && f >= anim.frameCount) {
                f = anim.loopFrom + (f - anim.loopFrom) % (anim.frameCount - anim.loopFrom);
            } else if (f >= anim.frameCount) {
                break;
            }
            for (FrameAnimation.Event e : anim.events) {
                if (e.frame == f) FrameAnimations.fireEvent(entityId, anim, e);
            }
        }
    }

    @Override
    public void setupAnim(float tickDelta) {
        time = timeAt(tickDelta);
        frame = frameFor(time);
        anim.sample(frame, pose);
        if (mirror) {
            System.arraycopy(pose, 0, tmp, 0, pose.length);
            Arrays.fill(pose, Float.NaN);
            for (FrameChannel c : FrameChannel.ALL) {
                float val = tmp[c.ordinal()];
                if (Float.isNaN(val)) continue;
                FrameChannel m = c.mirror();
                pose[m.ordinal()] = val * c.mirrorSign();
            }
        }

        // вес: плавный выход в конце и по команде «стоп» (вход — через затухание Player Animator)
        float w = 1f;
        if (!loops() && !anim.holdLast && Double.isNaN(frozenFrame) && time > anim.duration()) {
            w *= 1f - smooth((time - anim.duration()) / Math.max(1.0E-4, anim.fadeOut));
        }
        if (stopping) w *= 1f - smooth((time - stopAt) / stopFade);
        weight = w;

        Arrays.fill(add, 0f);
        if (secondary != null) {
            long now = System.nanoTime();
            float dt = lastSetupNanos < 0 ? 0f : (now - lastSetupNanos) / 1.0E9f;
            lastSetupNanos = now;
            Entity e = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getEntity(entityId) : null;
            secondary.apply(pose, add, Math.max(0f, dt), time, e);
        }
    }

    private static float smooth(double v) {
        if (v <= 0.0) return 0f;
        if (v >= 1.0) return 1f;
        return (float) (v * v * (3.0 - 2.0 * v));
    }

    private float chan(FrameChannel c) {
        return pose[c.ordinal()];
    }

    /** Абсолютный канал: смешать ваниль с позой кадра по весу и добавить живой слой. */
    private float abs(float vanilla, FrameChannel c) {
        float p = pose[c.ordinal()];
        float base = Float.isNaN(p) ? vanilla : vanilla + (p - vanilla) * weight;
        return base + add[c.ordinal()] * weight;
    }

    /** Добавочный канал (смещение частей, голова в режиме additive). */
    private float rel(float vanilla, FrameChannel c) {
        float p = pose[c.ordinal()];
        float off = (Float.isNaN(p) ? 0f : p) + add[c.ordinal()];
        return vanilla + off * weight;
    }

    @Override
    public Vec3f get3DTransform(String part, TransformType type, float tickDelta, Vec3f v0) {
        if (weight <= 0.0001f) return v0;
        float x = v0.getX(), y = v0.getY(), z = v0.getZ();
        switch (part) {
            case "body" -> {
                if (type == TransformType.POSITION) {
                    return new Vec3f(abs(x, FrameChannel.BODY_X), abs(y, FrameChannel.BODY_Y), abs(z, FrameChannel.BODY_Z));
                }
                if (type == TransformType.ROTATION) {
                    return new Vec3f(abs(x, FrameChannel.BODY_PITCH), abs(y, FrameChannel.BODY_YAW), abs(z, FrameChannel.BODY_ROLL));
                }
                return v0;
            }
            case "head" -> {
                if (type == TransformType.POSITION) {
                    return new Vec3f(rel(x, FrameChannel.HEAD_X), rel(y, FrameChannel.HEAD_Y), rel(z, FrameChannel.HEAD_Z));
                }
                if (type == TransformType.ROTATION) {
                    if (anim.headMode == FrameAnimation.HeadMode.ADDITIVE) {
                        return new Vec3f(rel(x, FrameChannel.HEAD_PITCH), rel(y, FrameChannel.HEAD_YAW), rel(z, FrameChannel.HEAD_ROLL));
                    }
                    return new Vec3f(abs(x, FrameChannel.HEAD_PITCH), abs(y, FrameChannel.HEAD_YAW), abs(z, FrameChannel.HEAD_ROLL));
                }
                return v0;
            }
            case "torso" -> {
                return limb(type, x, y, z, v0, FrameChannel.TORSO_X, FrameChannel.TORSO_Y, FrameChannel.TORSO_Z,
                        FrameChannel.TORSO_PITCH, FrameChannel.TORSO_YAW, FrameChannel.TORSO_ROLL,
                        FrameChannel.TORSO_BEND, FrameChannel.TORSO_BEND_AXIS);
            }
            case "rightArm" -> {
                return limb(type, x, y, z, v0, FrameChannel.RIGHT_ARM_X, FrameChannel.RIGHT_ARM_Y, FrameChannel.RIGHT_ARM_Z,
                        FrameChannel.RIGHT_ARM_PITCH, FrameChannel.RIGHT_ARM_YAW, FrameChannel.RIGHT_ARM_ROLL,
                        FrameChannel.RIGHT_ARM_BEND, FrameChannel.RIGHT_ARM_BEND_AXIS);
            }
            case "leftArm" -> {
                return limb(type, x, y, z, v0, FrameChannel.LEFT_ARM_X, FrameChannel.LEFT_ARM_Y, FrameChannel.LEFT_ARM_Z,
                        FrameChannel.LEFT_ARM_PITCH, FrameChannel.LEFT_ARM_YAW, FrameChannel.LEFT_ARM_ROLL,
                        FrameChannel.LEFT_ARM_BEND, FrameChannel.LEFT_ARM_BEND_AXIS);
            }
            case "rightLeg" -> {
                return limb(type, x, y, z, v0, FrameChannel.RIGHT_LEG_X, FrameChannel.RIGHT_LEG_Y, FrameChannel.RIGHT_LEG_Z,
                        FrameChannel.RIGHT_LEG_PITCH, FrameChannel.RIGHT_LEG_YAW, FrameChannel.RIGHT_LEG_ROLL,
                        FrameChannel.RIGHT_LEG_BEND, FrameChannel.RIGHT_LEG_BEND_AXIS);
            }
            case "leftLeg" -> {
                return limb(type, x, y, z, v0, FrameChannel.LEFT_LEG_X, FrameChannel.LEFT_LEG_Y, FrameChannel.LEFT_LEG_Z,
                        FrameChannel.LEFT_LEG_PITCH, FrameChannel.LEFT_LEG_YAW, FrameChannel.LEFT_LEG_ROLL,
                        FrameChannel.LEFT_LEG_BEND, FrameChannel.LEFT_LEG_BEND_AXIS);
            }
            default -> {
                return v0;
            }
        }
    }

    private Vec3f limb(TransformType type, float x, float y, float z, Vec3f v0,
                       FrameChannel px, FrameChannel py, FrameChannel pz,
                       FrameChannel pitch, FrameChannel yaw, FrameChannel roll,
                       FrameChannel bend, FrameChannel axis) {
        if (type == TransformType.POSITION) return new Vec3f(rel(x, px), rel(y, py), rel(z, pz));
        if (type == TransformType.ROTATION) return new Vec3f(abs(x, pitch), abs(y, yaw), abs(z, roll));
        if (type == TransformType.BEND) return new Vec3f(abs(x, axis), abs(y, bend), z);
        return v0;
    }

    @Override
    public FirstPersonMode getFirstPersonMode(float tickDelta) {
        if (anim.fpUntil >= 0 && frame >= anim.fpUntil) return FirstPersonMode.NONE;
        return switch (anim.firstPerson) {
            case MODEL -> FirstPersonMode.THIRD_PERSON_MODEL;
            case VANILLA -> FirstPersonMode.VANILLA;
            default -> FirstPersonMode.NONE;
        };
    }

    @Override
    public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
        boolean r = mirror ? anim.fpLeftArm : anim.fpRightArm;
        boolean l = mirror ? anim.fpRightArm : anim.fpLeftArm;
        return new FirstPersonConfiguration(r, l, r, l);
    }

    /** Для отладки и экспорта: текущая поза (внутренние единицы). */
    float[] currentPose() {
        return pose;
    }

    float weight() {
        return weight;
    }

    float channelNow(FrameChannel c) {
        return chan(c);
    }
}
