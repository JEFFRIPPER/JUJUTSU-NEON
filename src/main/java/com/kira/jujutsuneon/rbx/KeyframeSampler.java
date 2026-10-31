package com.kira.jujutsuneon.rbx;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.Keyframe;
import software.bernie.geckolib.core.keyframe.KeyframeStack;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.core.molang.MolangQueries;

import java.util.List;

/**
 * Значения ключей GeckoLib-анимации в любой момент времени — так же, как их считает GeckoLib
 * (AnimationController#getAnimationPointAtTick + EasingType), с двумя уточнениями, чтобы в игре было то же,
 * что в Blockbench:
 *  - catmullrom — честный сплайн по соседним ключам (у GeckoLib касательная в начале отрезка теряет предыдущий ключ);
 *  - step — значение держится до следующего ключа.
 * Сам GeckoLib не изменяется.
 */
public final class KeyframeSampler {

    private KeyframeSampler() {
    }

    /**
     * Поза анимации в момент time (секунды) — в out (повороты кватернионами в соглашении GeoBone).
     * mirror — зеркально (правое ↔ левое).
     */
    public static void sample(Clip clip, double time, boolean mirror, Pose out) {
        out.ensure(Bones.count());
        double tick = time * 20.0;
        double animTime = tick;
        MolangParser.INSTANCE.setValue(MolangQueries.ANIM_TIME, () -> animTime / 20.0);
        BoneAnimation[] anims = clip.animation.boneAnimations();
        int[] idx = clip.boneIndices();
        out.ensure(Bones.count());
        for (int i = 0; i < anims.length; i++) {
            BoneAnimation ba = anims[i];
            int b = idx[i];
            if (mirror) b = Bones.mirrored(b);
            KeyframeStack<Keyframe<IValue>> r = ba.rotationKeyFrames();
            if (!r.xKeyframes().isEmpty()) {
                double x = axis(r.xKeyframes(), tick, true, 0);
                double y = axis(r.yKeyframes(), tick, true, 1);
                double z = axis(r.zKeyframes(), tick, true, 2);
                if (mirror) {
                    y = -y;
                    z = -z;
                }
                QMath.fromEulerZYX(x, y, z, out.rot[b]);
                out.mask[b] |= Pose.ROT;
            }
            KeyframeStack<Keyframe<IValue>> p = ba.positionKeyFrames();
            if (!p.xKeyframes().isEmpty()) {
                double[] o = out.pos[b];
                o[0] = axis(p.xKeyframes(), tick, false, 0);
                o[1] = axis(p.yKeyframes(), tick, false, 1);
                o[2] = axis(p.zKeyframes(), tick, false, 2);
                if (mirror) o[0] = -o[0];
                out.mask[b] |= Pose.POS;
            }
            KeyframeStack<Keyframe<IValue>> s = ba.scaleKeyFrames();
            if (!s.xKeyframes().isEmpty()) {
                double[] o = out.scale[b];
                o[0] = axis(s.xKeyframes(), tick, false, 0);
                o[1] = axis(s.yKeyframes(), tick, false, 1);
                o[2] = axis(s.zKeyframes(), tick, false, 2);
                out.mask[b] |= Pose.SCALE;
            }
        }
    }

    /** Значение одной оси (радианы для поворотов, как в GeoBone). */
    static double axis(List<Keyframe<IValue>> frames, double tick, boolean rotation, int axis) {
        int n = frames.size();
        if (n == 0) return 0;
        double total = 0;
        int idx = n - 1;
        double local = tick;
        for (int i = 0; i < n; i++) {
            Keyframe<IValue> k = frames.get(i);
            total += k.length();
            if (total > tick) {
                idx = i;
                local = tick - (total - k.length());
                break;
            }
        }
        Keyframe<IValue> k = frames.get(idx);
        double end = value(k.endValue(), rotation, axis);
        if (local >= k.length()) return end;
        double start = value(k.startValue(), rotation, axis);
        double t = k.length() > 0 ? local / k.length() : 1;
        EasingType e = k.easingType();
        if (e == EasingType.CATMULLROM) {
            double p0 = idx > 0 ? value(frames.get(idx - 1).startValue(), rotation, axis) : start;
            double p3 = idx + 1 < n ? value(frames.get(idx + 1).endValue(), rotation, axis) : end;
            return EasingType.CatmullRomEasing.getPointOnSpline(t, p0, start, end, p3);
        }
        if (e == EasingType.STEP) return start;
        if (e == EasingType.LINEAR) return start + (end - start) * t;
        // остальные сглаживания — средствами GeckoLib
        return e.apply(new AnimationPoint(k, local, k.length(), start, end));
    }

    private static double value(IValue v, boolean rotation, int axis) {
        double d = v.get();
        if (rotation && !(v instanceof Constant)) {
            // как в GeckoLib: молang-значения поворота — градусы Blockbench, константы уже переведены при загрузке
            d = Math.toRadians(d);
            if (axis != 2) d = -d;
        }
        return d;
    }
}
