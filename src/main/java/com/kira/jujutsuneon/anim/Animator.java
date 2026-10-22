package com.kira.jujutsuneon.anim;

/**
 * Аниматор одного игрока: базовая поза (движение или ваниль) и слои клипов поверх неё.
 *
 * Как в Epic Fight, ни одна смена анимации не идёт рывком:
 *  - новый клип на свободном слое плавно набирает вес поверх живой нижней позы;
 *  - новый клип на занятом слое начинается из «снимка» последней показанной позы
 *    и за fadeIn плавно уходит в себя (переход-связка);
 *  - закончившийся клип за fadeOut плавно отдаёт кости нижним слоям.
 * Время — игровые тики + доля тика, поза считается в каждом кадре отрисовки.
 */
public final class Animator {

    /** Слои снизу вверх. */
    public static final int LAYER_ACTION = 0;
    public static final int LAYER_HIT = 1;
    public static final int LAYER_DEBUG = 2;
    public static final int LAYERS = 3;

    /** Параметры запуска клипа. */
    public static final class Play {
        public double speed = 1;
        public boolean mirror;
        /** Плавный вход, секунды (NaN — из клипа). */
        public double fadeIn = Double.NaN;
        /** Плавный выход, секунды (NaN — из клипа). */
        public double fadeOut = Double.NaN;
        /** С какого времени клипа начать, секунды. */
        public double startAt;
        /** Петля поверх настройки клипа (null — как в клипе). */
        public Boolean loop;
        /** Стоп-кадр: время клипа, секунды (NaN — играет). */
        public double freezeAt = Double.NaN;

        public Play speed(double s) {
            speed = s;
            return this;
        }

        public Play mirror(boolean m) {
            mirror = m;
            return this;
        }

        public Play fadeIn(double s) {
            fadeIn = s;
            return this;
        }

        public Play fadeOut(double s) {
            fadeOut = s;
            return this;
        }

        public Play startAt(double s) {
            startAt = s;
            return this;
        }

        public Play loop(Boolean l) {
            loop = l;
            return this;
        }

        public Play freezeAt(double s) {
            freezeAt = s;
            return this;
        }
    }

    private static final class Layer {
        Clip clip;
        double start;
        double speed = 1;
        boolean mirror;
        boolean loop;
        double fadeIn, fadeOut;
        double freezeAt = Double.NaN;
        /** Вход: 0 — плавный набор веса поверх нижней позы, 1 — связка из снимка. */
        boolean fromSnapshot;
        final Pose from = new Pose();
        final boolean[] fromMask = new boolean[Joint.COUNT];
        double fadeOutStart = Double.NaN;
        double fadeOutLen;
        boolean active;
        /** Последний вес слоя (для снимка маски при следующем запуске). */
        final double[] lastWeight = new double[Joint.COUNT];

        double clipTime(double now) {
            if (!Double.isNaN(freezeAt)) return freezeAt;
            return (now - start) * speed;
        }
    }

    private final Layer[] layers = new Layer[LAYERS];
    private final Pose composite = new Pose();
    private final Pose lastComposite = new Pose();
    private final Pose sample = new Pose();
    private final double[] tmpQ = new double[4];
    private boolean hasLast;
    private int ticks;

    /** Итог для «взгляда»: сколько голова следит за камерой и сколько корпус поворачивается за ней. */
    public double lookWeight, followWeight;

    public Animator() {
        for (int i = 0; i < LAYERS; i++) layers[i] = new Layer();
    }

    public void tick() {
        ticks++;
    }

    public int ticks() {
        return ticks;
    }

    /** Время аниматора, секунды. */
    public double time(double partialTick) {
        return (ticks + partialTick) / 20.0;
    }

    public void play(int layer, Clip clip, Play opts, double now) {
        Layer l = layers[layer];
        boolean wasActive = l.active && layerWeightNow(l, now) > 1.0E-3;
        l.fromSnapshot = wasActive && hasLast;
        if (l.fromSnapshot) {
            l.from.copyFrom(lastComposite);
            for (int j = 0; j < Joint.COUNT; j++) l.fromMask[j] = l.lastWeight[j] > 1.0E-4 || clip.mask[j] > 0;
        }
        l.clip = clip;
        l.speed = (opts.speed <= 0 ? 1 : opts.speed) * clip.speed;
        l.mirror = opts.mirror;
        l.loop = opts.loop != null ? opts.loop : clip.loop;
        l.fadeIn = Double.isNaN(opts.fadeIn) ? clip.fadeIn : opts.fadeIn;
        l.fadeOut = Double.isNaN(opts.fadeOut) ? clip.fadeOut : opts.fadeOut;
        l.freezeAt = opts.freezeAt;
        l.start = now - opts.startAt / l.speed;
        l.fadeOutStart = Double.NaN;
        l.active = true;
    }

    /** Плавно остановить слой за fade секунд (NaN — по клипу). */
    public void stop(int layer, double fade, double now) {
        Layer l = layers[layer];
        if (!l.active || !Double.isNaN(l.fadeOutStart)) return;
        l.fadeOutStart = now;
        l.fadeOutLen = Double.isNaN(fade) ? l.fadeOut : fade;
    }

    public void stopNow(int layer) {
        layers[layer].active = false;
    }

    public boolean isPlaying(int layer) {
        return layers[layer].active;
    }

    public Clip clip(int layer) {
        Layer l = layers[layer];
        return l.active ? l.clip : null;
    }

    public double clipTime(int layer, double now) {
        Layer l = layers[layer];
        return l.active ? l.clipTime(now) : -1;
    }

    /** Стоп-кадр на слое (для отладки): время клипа или NaN — играть дальше с этого места. */
    public void freeze(int layer, double clipTime, double now) {
        Layer l = layers[layer];
        if (!l.active) return;
        if (Double.isNaN(clipTime)) {
            if (!Double.isNaN(l.freezeAt)) l.start = now - l.freezeAt / l.speed;
            l.freezeAt = Double.NaN;
        } else {
            l.freezeAt = clipTime;
        }
    }

    public boolean anyActive() {
        for (Layer l : layers) if (l.active) return true;
        return false;
    }

    private double layerWeightNow(Layer l, double now) {
        if (!l.active) return 0;
        double w = 1;
        double t = l.clipTime(now);
        if (!l.fromSnapshot && l.fadeIn > 1.0E-4 && Double.isNaN(l.freezeAt)) {
            w *= AnimMath.smoother((now - l.start) / l.fadeIn);
        }
        double endFade = Double.NaN;
        if (!l.loop && !l.clip.holdLast && Double.isNaN(l.freezeAt) && t >= l.clip.length) {
            endFade = AnimMath.smoother((t - l.clip.length) / l.speed / Math.max(1.0E-4, l.fadeOut));
        }
        if (!Double.isNaN(endFade)) w *= 1 - endFade;
        if (!Double.isNaN(l.fadeOutStart)) {
            w *= 1 - AnimMath.smoother((now - l.fadeOutStart) / Math.max(1.0E-4, l.fadeOutLen));
        }
        return w;
    }

    /**
     * Посчитать итоговую позу.
     *
     * @param base     нижняя поза (движение или ванильная), не меняется
     * @param baseLook сколько голова базовой позы следит за взглядом
     * @param out      итог (до «взгляда» — его накладывает {@link #applyLook})
     */
    public void evaluate(double now, Pose base, double baseLook, double baseFollow, Pose out) {
        composite.copyFrom(base);
        double look = baseLook, follow = baseFollow;
        int head = Joint.HEAD.ordinal(), waist = Joint.WAIST.ordinal();
        for (Layer l : layers) {
            if (!l.active) {
                java.util.Arrays.fill(l.lastWeight, 0);
                continue;
            }
            double w = layerWeightNow(l, now);
            boolean finished = w <= 1.0E-4 && (!Double.isNaN(l.fadeOutStart)
                    || (!l.loop && !l.clip.holdLast && l.clipTime(now) >= l.clip.length));
            if (finished) {
                l.active = false;
                java.util.Arrays.fill(l.lastWeight, 0);
                continue;
            }
            double t = l.clipTime(now);
            if (l.loop) t = t % l.clip.length;
            l.clip.sample(t, l.mirror, sample, tmpQ);
            if (l.clip.additive) {
                for (int j = 0; j < Joint.COUNT; j++) {
                    double k = w * sample.weight[j];
                    composite.addJoint(sample, j, k, tmpQ);
                    l.lastWeight[j] = 0;
                }
            } else {
                composite.blendTowards(sample, w);
                for (int j = 0; j < Joint.COUNT; j++) l.lastWeight[j] = w * sample.weight[j];
                double kh = w * sample.weight[head];
                look += (l.clip.look - look) * kh;
                follow += (0 - follow) * (w * sample.weight[waist]);
            }
            if (l.fromSnapshot && l.fadeIn > 1.0E-4) {
                double u = AnimMath.smoother((now - l.start) / l.fadeIn);
                if (u < 1) {
                    for (int j = 0; j < Joint.COUNT; j++) {
                        if (l.fromMask[j]) composite.mixJoint(l.from, j, 1 - u);
                    }
                } else {
                    l.fromSnapshot = false;
                }
            }
        }
        lastComposite.copyFrom(composite);
        hasLast = true;
        out.copyFrom(composite);
        lookWeight = look;
        followWeight = follow;
    }

    /** Активный клип с видом от первого лица (верхний слой), или null. */
    public Clip firstPersonClip(double now) {
        for (int i = LAYERS - 1; i >= 0; i--) {
            Layer l = layers[i];
            if (!l.active || l.clip.firstPerson == Clip.FirstPerson.NONE) continue;
            if (l.clip.fpUntil >= 0 && l.clipTime(now) >= l.clip.fpUntil) continue;
            if (layerWeightNow(l, now) <= 0.05) continue;
            return l.clip;
        }
        return null;
    }

    public boolean firstPersonMirrored() {
        for (int i = LAYERS - 1; i >= 0; i--) {
            Layer l = layers[i];
            if (l.active && l.clip.firstPerson != Clip.FirstPerson.NONE) return l.mirror;
        }
        return false;
    }

    // ------------------------------------------------------------------ взгляд

    private static final double[] T1 = new double[4], T2 = new double[4], T3 = new double[4];

    /**
     * Голова следит за камерой: поворот головы := (корпус⁻¹ · тело⁻¹ᵏ · взгляд)^look · поза головы.
     * Корпус поворачивается за взглядом на долю follow (живее, как у настоящего человека).
     *
     * @param yaw        поворот головы относительно тела (рад, +вправо), как netHeadYaw
     * @param pitch      наклон головы (рад, +вниз), как headPitch
     * @param rootComp   насколько гасить наклон всего тела для взгляда (0..1; в полёте — 0,8)
     */
    public static void applyLook(Pose p, double yaw, double pitch, double look, double follow, double rootComp) {
        int w = Joint.WAIST.ordinal(), h = Joint.HEAD.ordinal(), r = Joint.ROOT.ordinal();
        if (p.vanilla[h] >= 0.999) return;
        if (follow > 1.0E-4 && p.vanilla[w] < 0.999) {
            AnimMath.fromEuler(pitch * 0.12 * follow, yaw * 0.28 * follow, 0, T1);
            AnimMath.mul(T1, p.rot[w], p.rot[w]);
        }
        if (look <= 1.0E-4) return;
        {
            // взгляд в пространстве модели
            AnimMath.fromEuler(pitch, yaw, 0, T1);
            if (rootComp > 1.0E-4) {
                double[] q = p.rot[r];
                // тело в пространстве модели: (−x, −y, z, w); гасим его долю rootComp
                T2[0] = q[0];
                T2[1] = q[1];
                T2[2] = -q[2];
                T2[3] = q[3];
                AnimMath.setIdentity(T3);
                AnimMath.slerp(T3, T2, rootComp, T3);
                AnimMath.mul(T3, T1, T1);
            }
            AnimMath.conjugate(p.rot[w], T2);
            AnimMath.mul(T2, T1, T1);
            AnimMath.setIdentity(T3);
            AnimMath.slerp(T3, T1, Math.min(1, look), T3);
            AnimMath.mul(T3, p.rot[h], p.rot[h]);
        }
    }
}
