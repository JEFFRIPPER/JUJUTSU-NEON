package com.kira.jujutsuneon.rbx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аниматор персонажа — как Animator в Roblox: загружает анимации в треки и смешивает все играющие треки
 * в одну позу.
 *
 * Правила смешивания (как в Roblox):
 *  - приоритеты идут снизу вверх (Core → Action4); трек влияет только на кости, у которых есть ключи;
 *  - треки одного приоритета смешиваются по весам (повороты — через кватернионы);
 *  - если сумма весов на приоритете меньше 1, сквозь него видно нижние приоритеты:
 *    поза = lerp(нижние, смесь приоритета, min(1, сумма весов));
 *  - трек с @blend=additive прибавляется поверх всего (своё расширение).
 */
public final class Animator {

    public record MarkerEvent(AnimationTrack track, Clip.Marker marker) {
    }

    public record SoundEvent(AnimationTrack track, Clip.SoundCue cue) {
    }

    public record ParticleEvent(AnimationTrack track, Clip.ParticleCue cue) {
    }

    /** Что ещё выдаёт смесь, кроме позы. */
    public static final class Mix {
        /** Насколько голова следит за взглядом (0..1). */
        public double look = 1;
        /** Насколько плечи следят за взглядом (прицел), по рукам. */
        public double aimRight, aimLeft;
        /** Руки модели от первого лица: режим и вес самого важного трека, который этого просит. */
        public Clip.FirstPerson firstPerson = Clip.FirstPerson.NONE;
        public double firstPersonWeight;
        /** Максимальный вес играющих треков по приоритетам (для логики «что сейчас главнее»). */
        public final double[] priorityWeight = new double[AnimationPriority.values().length];
    }

    public final Signal<AnimationTrack> animationPlayed = new Signal<>();
    public final Signal<MarkerEvent> markerReached = new Signal<>();
    public final Signal<SoundEvent> soundCue = new Signal<>();
    public final Signal<ParticleEvent> particleCue = new Signal<>();

    private final List<AnimationTrack> active = new ArrayList<>();
    private final Map<Clip, AnimationTrack> shared = new IdentityHashMap<>();
    private double clock;
    private long serial;

    // рабочие буферы смешивания
    private final Pose sample = new Pose();
    private double[] wRot = new double[0], wPos = new double[0], wScale = new double[0];
    private double[][] qAcc = new double[0][], pAcc = new double[0][], sAcc = new double[0][];
    private final double[] tq = new double[4];

    // ------------------------------------------------------------------ треки

    /** Новый трек для анимации (как Animator:LoadAnimation). */
    public AnimationTrack loadAnimation(Clip clip) {
        return new AnimationTrack(this, clip);
    }

    /** Новый трек по имени анимации; null — если такой нет. */
    public AnimationTrack loadAnimation(String name) {
        Clip c = ClipLibrary.get(name);
        return c == null ? null : loadAnimation(c);
    }

    /** Общий трек для анимации (один на аниматор) — удобно, когда анимацию просто запускают и останавливают. */
    public AnimationTrack track(Clip clip) {
        return shared.computeIfAbsent(clip, this::loadAnimation);
    }

    /** Играющие треки (как Animator:GetPlayingAnimationTracks). */
    public List<AnimationTrack> getPlayingAnimationTracks() {
        List<AnimationTrack> out = new ArrayList<>();
        for (AnimationTrack t : active) {
            if (t.isPlaying()) out.add(t);
        }
        return out;
    }

    /** Все треки, которые ещё влияют на позу (включая плавно уходящие). */
    public List<AnimationTrack> activeTracks() {
        return Collections.unmodifiableList(active);
    }

    public void stopAll(double fade) {
        for (AnimationTrack t : new ArrayList<>(active)) t.stop(fade);
    }

    /** Остановить все треки приоритета не ниже from (например, все техники). */
    public void stopFrom(AnimationPriority from, double fade) {
        for (AnimationTrack t : new ArrayList<>(active)) {
            if (t.getPriority().ordinal() >= from.ordinal()) t.stop(fade);
        }
    }

    /** Играющий трек анимации с таким именем (или null). */
    public AnimationTrack playing(String name) {
        for (AnimationTrack t : active) {
            if (t.isPlaying() && t.clip.name.equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    public double clock() {
        return clock;
    }

    long nextSerial() {
        return ++serial;
    }

    void activate(AnimationTrack t) {
        if (!t.isActive()) {
            t.markActive(true);
            active.add(t);
        }
    }

    void deactivate(AnimationTrack t) {
        t.markActive(false);
        active.remove(t);
    }

    /** Продвинуть все треки на dt секунд (раз в кадр). */
    public void step(double dt) {
        clock += dt;
        if (active.isEmpty()) return;
        for (AnimationTrack t : active.toArray(new AnimationTrack[0])) t.step(dt);
    }

    // ------------------------------------------------------------------ смесь

    private void ensure(int n) {
        if (wRot.length >= n) return;
        wRot = new double[n];
        wPos = new double[n];
        wScale = new double[n];
        qAcc = new double[n][4];
        pAcc = new double[n][3];
        sAcc = new double[n][3];
    }

    /** Смешать все активные треки в позу out. */
    public void evaluate(Pose out, Mix mix) {
        int n = Bones.count();
        out.clear();
        sample.ensure(n);
        ensure(n);
        mix.look = 1;
        mix.aimRight = mix.aimLeft = 0;
        mix.firstPerson = Clip.FirstPerson.NONE;
        mix.firstPersonWeight = 0;
        java.util.Arrays.fill(mix.priorityWeight, 0);
        if (active.isEmpty()) return;
        int head = Bones.find(Bones.HEAD);
        int rArm = Bones.find(Bones.R_UPPER_ARM), lArm = Bones.find(Bones.L_UPPER_ARM);
        int fpPriority = -1;

        AnimationPriority[] prs = AnimationPriority.values();
        for (AnimationPriority p : prs) {
            boolean any = false;
            double lookAcc = 0, lookW = 0, aimRAcc = 0, aimRW = 0, aimLAcc = 0, aimLW = 0;
            for (AnimationTrack t : active) {
                if (t.getPriority() != p || t.clip.additive) continue;
                double w = t.getWeightCurrent();
                if (w <= 1.0E-4) continue;
                if (!any) {
                    any = true;
                    java.util.Arrays.fill(wRot, 0, n, 0);
                    java.util.Arrays.fill(wPos, 0, n, 0);
                    java.util.Arrays.fill(wScale, 0, n, 0);
                }
                mix.priorityWeight[p.ordinal()] = Math.max(mix.priorityWeight[p.ordinal()], w);
                java.util.Arrays.fill(sample.mask, 0);
                KeyframeSampler.sample(t.clip, t.getTimePosition(), t.isMirrored(), sample);
                int m = Math.min(n, sample.mask.length);
                for (int b = 0; b < m; b++) {
                    int mk = sample.mask[b];
                    if (mk == 0) continue;
                    if ((mk & Pose.ROT) != 0) {
                        double[] q = sample.rot[b];
                        double[] a = qAcc[b];
                        if (wRot[b] == 0) {
                            a[0] = q[0] * w;
                            a[1] = q[1] * w;
                            a[2] = q[2] * w;
                            a[3] = q[3] * w;
                        } else {
                            double s = (a[0] * q[0] + a[1] * q[1] + a[2] * q[2] + a[3] * q[3]) < 0 ? -w : w;
                            a[0] += q[0] * s;
                            a[1] += q[1] * s;
                            a[2] += q[2] * s;
                            a[3] += q[3] * s;
                        }
                        wRot[b] += w;
                    }
                    if ((mk & Pose.POS) != 0) {
                        double[] v = sample.pos[b], a = pAcc[b];
                        if (wPos[b] == 0) a[0] = a[1] = a[2] = 0;
                        a[0] += v[0] * w;
                        a[1] += v[1] * w;
                        a[2] += v[2] * w;
                        wPos[b] += w;
                    }
                    if ((mk & Pose.SCALE) != 0) {
                        double[] v = sample.scale[b], a = sAcc[b];
                        if (wScale[b] == 0) a[0] = a[1] = a[2] = 0;
                        a[0] += v[0] * w;
                        a[1] += v[1] * w;
                        a[2] += v[2] * w;
                        wScale[b] += w;
                    }
                }
                if (head >= 0 && head < m && (sample.mask[head] & Pose.ROT) != 0) {
                    lookAcc += t.clip.look * w;
                    lookW += w;
                }
                if (t.clip.aim > 0) {
                    if (rArm >= 0 && rArm < m && sample.mask[rArm] != 0) {
                        aimRAcc += t.clip.aim * w;
                        aimRW += w;
                    }
                    if (lArm >= 0 && lArm < m && sample.mask[lArm] != 0) {
                        aimLAcc += t.clip.aim * w;
                        aimLW += w;
                    }
                }
                Clip.FirstPerson fp = t.firstPerson();
                if (fp != Clip.FirstPerson.NONE && w > 0.05 && p.ordinal() >= fpPriority) {
                    if (p.ordinal() > fpPriority || w > mix.firstPersonWeight) {
                        fpPriority = p.ordinal();
                        mix.firstPerson = fp;
                        mix.firstPersonWeight = w;
                    }
                }
            }
            if (!any) continue;
            for (int b = 0; b < n; b++) {
                double w = wRot[b];
                if (w > 0) {
                    double[] a = qAcc[b];
                    QMath.normalize(a);
                    double k = Math.min(1, w);
                    if ((out.mask[b] & Pose.ROT) == 0) QMath.identity(out.rot[b]);
                    if (k >= 1) QMath.copy(a, out.rot[b]);
                    else QMath.slerp(out.rot[b], a, k, out.rot[b]);
                    out.mask[b] |= Pose.ROT;
                }
                w = wPos[b];
                if (w > 0) {
                    double k = Math.min(1, w);
                    double[] a = pAcc[b], o = out.pos[b];
                    for (int i = 0; i < 3; i++) o[i] += (a[i] / w - o[i]) * k;
                    out.mask[b] |= Pose.POS;
                }
                w = wScale[b];
                if (w > 0) {
                    double k = Math.min(1, w);
                    double[] a = sAcc[b], o = out.scale[b];
                    for (int i = 0; i < 3; i++) o[i] += (a[i] / w - o[i]) * k;
                    out.mask[b] |= Pose.SCALE;
                }
            }
            if (lookW > 0) mix.look += (lookAcc / lookW - mix.look) * Math.min(1, lookW);
            if (aimRW > 0) mix.aimRight += (aimRAcc / aimRW - mix.aimRight) * Math.min(1, aimRW);
            if (aimLW > 0) mix.aimLeft += (aimLAcc / aimLW - mix.aimLeft) * Math.min(1, aimLW);
        }

        // добавочные треки — поверх всего
        for (AnimationPriority p : prs) {
            for (AnimationTrack t : active) {
                if (t.getPriority() != p || !t.clip.additive) continue;
                double w = t.getWeightCurrent();
                if (w <= 1.0E-4) continue;
                java.util.Arrays.fill(sample.mask, 0);
                KeyframeSampler.sample(t.clip, t.getTimePosition(), t.isMirrored(), sample);
                int m = Math.min(n, sample.mask.length);
                for (int b = 0; b < m; b++) {
                    int mk = sample.mask[b];
                    if (mk == 0) continue;
                    if ((mk & Pose.ROT) != 0) {
                        QMath.identity(tq);
                        QMath.slerp(tq, sample.rot[b], Math.min(1, w), tq);
                        if ((out.mask[b] & Pose.ROT) == 0) QMath.identity(out.rot[b]);
                        QMath.mul(out.rot[b], tq, out.rot[b]);
                        out.mask[b] |= Pose.ROT;
                    }
                    if ((mk & Pose.POS) != 0) {
                        for (int i = 0; i < 3; i++) out.pos[b][i] += sample.pos[b][i] * w;
                        out.mask[b] |= Pose.POS;
                    }
                    if ((mk & Pose.SCALE) != 0) {
                        for (int i = 0; i < 3; i++) out.scale[b][i] *= 1 + (sample.scale[b][i] - 1) * w;
                        out.mask[b] |= Pose.SCALE;
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "Animator" + active;
    }
}
