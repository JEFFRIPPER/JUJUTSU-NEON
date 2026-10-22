package com.kira.jujutsuneon.anim;

/**
 * Анимация (клип): кривые каналов для каждой кости и настройки проигрывания.
 *
 * Каналы кости: pitch, yaw, roll (градусы), x, y, z (пиксели; у ROOT — блоки), bend, axis (градусы).
 * Кость, у которой есть хоть одна кривая, входит в маску клипа (вес 1, если в файле не задан другой);
 * остальные кости клип не трогает — их показывает нижний слой (движение или ваниль).
 */
public final class Clip {

    public static final int PITCH = 0, YAW = 1, ROLL = 2, X = 3, Y = 4, Z = 5, BEND = 6, AXIS = 7;
    public static final int CHANNELS = 8;
    public static final String[] CHANNEL_NAMES = {"pitch", "yaw", "roll", "x", "y", "z", "bend", "axis"};

    public enum FirstPerson { NONE, MODEL, VANILLA }

    private static final Joint[] WAIST_CHILDREN = {Joint.HEAD, Joint.RIGHT_ARM, Joint.LEFT_ARM};

    public final String name;
    /** Длина в секундах. */
    public final double length;
    public final boolean loop;
    /** Скорость проигрывания по умолчанию. */
    public final double speed;
    /** Плавный вход (переход из прошлой позы) и выход, секунды. */
    public final double fadeIn, fadeOut;
    /** После конца держать последний кадр, пока не остановят. */
    public final boolean holdLast;
    /** Добавочный клип: прибавляется к нижним слоям (присед при приземлении, отдача). */
    public final boolean additive;
    /** Сколько голова следит за взглядом игрока (0 — голова целиком из клипа). */
    public final double look;
    /**
     * Повороты головы и рук в клипе заданы относительно тела, а не корпуса (старый формат Player Animator):
     * при наклоне корпуса плечи и шея едут за ним, а направление рук и взгляда остаётся как в клипе.
     */
    public final boolean worldLimbs;
    public final FirstPerson firstPerson;
    public final boolean fpRightArm, fpLeftArm;
    /** До какого времени (с) держать вид от первого лица (−1 — весь клип). */
    public final double fpUntil;

    final Curve[][] curves;
    final double[] mask;

    Clip(Builder b) {
        this.name = b.name;
        this.length = Math.max(1.0E-3, b.length);
        this.loop = b.loop;
        this.speed = b.speed;
        this.fadeIn = b.fadeIn;
        this.fadeOut = b.fadeOut;
        this.holdLast = b.holdLast;
        this.additive = b.additive;
        this.look = b.look;
        this.worldLimbs = b.worldLimbs;
        this.firstPerson = b.firstPerson;
        this.fpRightArm = b.fpRightArm;
        this.fpLeftArm = b.fpLeftArm;
        this.fpUntil = b.fpUntil;
        this.curves = new Curve[Joint.COUNT][CHANNELS];
        this.mask = new double[Joint.COUNT];
        for (int j = 0; j < Joint.COUNT; j++) {
            boolean any = false;
            for (int c = 0; c < CHANNELS; c++) {
                Curve.Builder cb = b.tracks[j][c];
                if (cb == null || cb.isEmpty()) continue;
                curves[j][c] = cb.build(loop, this.length);
                any = true;
            }
            mask[j] = !Double.isNaN(b.mask[j]) ? b.mask[j] : (any ? 1.0 : 0.0);
        }
    }

    public boolean hasJoint(Joint j) {
        return mask[j.ordinal()] > 0;
    }

    public double maskOf(Joint j) {
        return mask[j.ordinal()];
    }

    public Curve curve(Joint j, int channel) {
        return curves[j.ordinal()][channel];
    }

    private double val(int j, int c, double t, double def) {
        Curve cv = curves[j][c];
        return cv == null ? def : cv.sample(t);
    }

    /**
     * Поза клипа в момент t (секунды от начала клипа). Петля — по модулю длины,
     * без петли — после конца держится последний кадр.
     */
    public void sample(double t, boolean mirror, Pose out, double[] tmpQ) {
        double time = t;
        if (!loop) time = Math.max(0, Math.min(length, t));
        for (int j = 0; j < Joint.COUNT; j++) {
            int src = mirror ? Joint.ALL[j].mirror().ordinal() : j;
            double m = mask[src];
            out.resetJoint(j);
            out.weight[j] = m;
            if (m <= 0) continue;
            double p = val(src, PITCH, time, 0), y = val(src, YAW, time, 0), r = val(src, ROLL, time, 0);
            double x = val(src, X, time, 0), oy = val(src, Y, time, 0), z = val(src, Z, time, 0);
            double bd = val(src, BEND, time, 0), ax = val(src, AXIS, time, 0);
            if (mirror) {
                y = -y;
                r = -r;
                x = -x;
                ax = -ax;
            }
            AnimMath.fromEuler(p * AnimMath.DEG, y * AnimMath.DEG, r * AnimMath.DEG, out.rot[j]);
            out.off[j][0] = x;
            out.off[j][1] = oy;
            out.off[j][2] = z;
            out.bend[j] = bd * AnimMath.DEG;
            out.bendAxis[j] = ax * AnimMath.DEG;
        }
        if (worldLimbs && out.weight[Joint.WAIST.ordinal()] > 0) {
            // Голова и руки старого формата заданы в пространстве тела: rot := waist⁻¹ · rot.
            AnimMath.conjugate(out.rot[Joint.WAIST.ordinal()], tmpQ);
            for (Joint jj : WAIST_CHILDREN) {
                int k = jj.ordinal();
                if (out.weight[k] > 0) AnimMath.mul(tmpQ, out.rot[k], out.rot[k]);
            }
        }
    }

    // ------------------------------------------------------------------ сборка

    public static final class Builder {
        final String name;
        double length = 1;
        boolean loop;
        double speed = 1;
        double fadeIn = 0.15, fadeOut = 0.2;
        boolean holdLast;
        boolean additive;
        double look;
        boolean worldLimbs;
        FirstPerson firstPerson = FirstPerson.NONE;
        boolean fpRightArm = true, fpLeftArm = true;
        double fpUntil = -1;
        final Curve.Builder[][] tracks = new Curve.Builder[Joint.COUNT][CHANNELS];
        final double[] mask = new double[Joint.COUNT];

        public Builder(String name) {
            this.name = name;
            java.util.Arrays.fill(mask, Double.NaN);
        }

        public Builder length(double s) {
            length = s;
            return this;
        }

        public Builder loop(boolean l) {
            loop = l;
            return this;
        }

        public Builder speed(double s) {
            speed = s;
            return this;
        }

        public Builder fade(double in, double out) {
            fadeIn = in;
            fadeOut = out;
            return this;
        }

        public Builder holdLast(boolean h) {
            holdLast = h;
            return this;
        }

        public Builder additive(boolean a) {
            additive = a;
            return this;
        }

        public Builder look(double l) {
            look = l;
            return this;
        }

        public Builder worldLimbs(boolean w) {
            worldLimbs = w;
            return this;
        }

        public Builder firstPerson(FirstPerson mode, boolean right, boolean left, double until) {
            firstPerson = mode;
            fpRightArm = right;
            fpLeftArm = left;
            fpUntil = until;
            return this;
        }

        public Builder mask(Joint j, double w) {
            mask[j.ordinal()] = w;
            return this;
        }

        public Builder key(Joint j, int channel, double time, double value, Curve.Mode mode) {
            Curve.Builder cb = tracks[j.ordinal()][channel];
            if (cb == null) tracks[j.ordinal()][channel] = cb = new Curve.Builder();
            cb.key(time, value, mode);
            return this;
        }

        public boolean has(Joint j, int channel) {
            Curve.Builder cb = tracks[j.ordinal()][channel];
            return cb != null && !cb.isEmpty();
        }

        public Clip build() {
            return new Clip(this);
        }
    }

    public static int channelByName(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "pitch", "rx", "xrot", "rotx" -> PITCH;
            case "yaw", "ry", "yrot", "roty" -> YAW;
            case "roll", "rz", "zrot", "rotz" -> ROLL;
            case "x", "px", "posx" -> X;
            case "y", "py", "posy" -> Y;
            case "z", "pz", "posz" -> Z;
            case "bend" -> BEND;
            case "axis", "bendaxis", "bend_axis" -> AXIS;
            default -> -1;
        };
    }
}
