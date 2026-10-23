package com.kira.jujutsuneon.rbx;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Скрипт движения — как стандартный Animate в Roblox: по состоянию персонажа запускает и смешивает
 * анимации стойки, ходьбы, бега, прыжка, падения, плавания, лазания, сидения; плюс движения мода (дэши,
 * полёт, зарядка прыжка) и позы рук с предметами.
 *
 * Анимации (имена в Blockbench), все необязательные — чего нет, то просто не играет:
 * <pre>
 *  Core:      idle, idle_look, walk, run, sprint, crouch_idle, crouch_walk, jump, fall, swim, swim_idle,
 *             climb, sit, glide, sleep
 *  Idle:      hold_item (правой рукой; левой — зеркально)
 *  Movement:  land, charge, leap, fly_hover, fly_glide, fly_boost, fly_land, dash_front, dash_back, dash_left, dash_right
 *  Action:    swing (взмах), use_eat, use_drink, use_block, use_bow, use_spear, use_crossbow, hold_crossbow,
 *             use_spyglass, use_horn, use_brush
 * </pre>
 * Как в Roblox, ходьба/бег/спринт играют одновременно с весами по скорости. В отличие от Roblox, фаза шагов
 * у них общая и идёт от пройденного пути (по @move_speed клипа), поэтому ноги не скользят и не путаются при смене.
 * Циклы шага рисуются так: правая нога ставится на землю в начале цикла, левая — в середине.
 */
public final class AnimateScript {

    /**
     * Наклоны всего тела поверх анимаций, градусы: вперёд (+) и вправо (+). На земле тело наклоняется вокруг ступней,
     * в полёте — вокруг таза ({@link #leanPivot}, пиксели над ступнями).
     */
    public double leanPitch, leanRoll, leanPivot;

    private final Animator animator;
    private final Map<String, Slot> slots = new HashMap<>();
    private final Random random = new Random();
    private int generation = -1;

    private double phase;
    private int phaseDir = 1;
    private double airTime, fallSpeed, jumpTime = -1;
    private boolean wasOnGround = true, initialized;
    private int lastDashKind = -1;
    private double lastDashAge;
    private double idleSwitch = 6;
    private boolean idleVariant;

    /** Трек скрипта: вес меняется линейно к цели (как fade в Roblox). */
    private static final class Slot {
        /** Какую анимацию играет слот (зеркальный слот играет ту же анимацию, что и обычный). */
        final String clipName;
        AnimationTrack track;
        double weight, target, fade = 0.2;
        boolean targeted;
        /** Время задаёт скрипт (общая фаза шагов, зарядка…), а не сам трек. */
        boolean driven;
        boolean mirror;

        Slot(String clipName) {
            this.clipName = clipName;
        }
    }

    public AnimateScript(Animator animator) {
        this.animator = animator;
    }

    // ------------------------------------------------------------------ управление слотами

    private Slot slot(String key, String clipName) {
        return slots.computeIfAbsent(key, k -> new Slot(clipName));
    }

    /** Цель веса для анимации в этом кадре (fade — за сколько секунд вес проходит 0→1). */
    private Slot want(String name, double weight, double fade) {
        return want(name, name, weight, fade);
    }

    /** То же для слота с отдельным ключом (например, зеркальная копия анимации). */
    private Slot want(String key, String clipName, double weight, double fade) {
        Slot s = slot(key, clipName);
        s.target = Math.max(s.targeted ? s.target : 0, weight);
        s.fade = fade;
        s.targeted = true;
        return s;
    }

    private void applySlots(double dt) {
        for (Map.Entry<String, Slot> en : slots.entrySet()) {
            Slot s = en.getValue();
            double target = s.targeted ? s.target : 0;
            s.targeted = false;
            s.target = 0;
            double rate = s.fade <= 1.0E-3 ? 1.0E9 : 1.0 / s.fade;
            if (s.weight < target) s.weight = Math.min(target, s.weight + rate * dt);
            else if (s.weight > target) s.weight = Math.max(target, s.weight - rate * dt);
            if (s.weight > 1.0E-4) {
                if (s.track == null || !s.track.isPlaying()) {
                    Clip c = ClipLibrary.get(s.clipName);
                    if (c == null) {
                        s.weight = 0;
                        continue;
                    }
                    if (s.track == null || s.track.clip != c) s.track = animator.loadAnimation(c);
                    s.track.play(0, s.weight, 1);
                    // пока слот нужен, неповторяемая анимация замирает на последнем кадре, а не уходит
                    s.track.setHoldAtEnd(true);
                    s.track.setFrozen(s.driven);
                }
                s.track.setMirrored(s.mirror);
                s.track.adjustWeight(s.weight, 0);
            } else if (s.track != null && s.track.isActive()) {
                s.track.stop(0);
            }
        }
    }

    private Clip clip(String name) {
        return ClipLibrary.get(name);
    }

    /** Запустить одиночную анимацию (прыжок, приземление, взмах) с начала. */
    private void fire(String name, double fade, double weight, double speed, boolean mirror) {
        Clip c = clip(name);
        if (c == null) return;
        AnimationTrack t = animator.track(c);
        t.setMirrored(mirror);
        t.play(fade, weight, speed);
    }

    // ------------------------------------------------------------------ кадр

    public void update(AnimateInput in) {
        double dt = Math.max(0, Math.min(in.dt, 0.25));
        int gen = ClipLibrary.generation();
        if (gen != generation) {
            // анимации перезагрузились — треки пересоздадутся по новым
            generation = gen;
            for (Slot s : slots.values()) {
                if (s.track != null) s.track.stop(0);
                s.track = null;
            }
        }
        if (!initialized) {
            wasOnGround = in.onGround;
            initialized = true;
        }

        boolean air = !in.onGround && !in.inWater && !in.climbing && !in.riding && !in.fallFlying
                && !in.swimming && in.flightW < 0.5;
        // ---------------- воздух: прыжок → падение, приземление
        if (!in.onGround) {
            airTime += dt;
            fallSpeed = Math.max(fallSpeed, -in.vy);
        }
        if (wasOnGround && !in.onGround && in.vy > 1.0 && air && in.leapAge < 0) {
            jumpTime = 0;
            // новый прыжок — анимация прыжка с начала (как в Roblox)
            Slot j = slots.get("jump");
            if (j != null && j.track != null && j.track.isPlaying()) j.track.setTimePosition(0);
        }
        if (jumpTime >= 0) jumpTime += dt;
        if (in.onGround && !wasOnGround) {
            if (airTime > 0.25 && in.flightW < 0.3 && in.leapAge < 0) {
                double s = clamp((fallSpeed - 3) / 15.0, 0, 1);
                fire("land", 0.05, 0.35 + 0.65 * s, 1, false);
            }
            jumpTime = -1;
        }
        if (in.onGround) {
            airTime = 0;
            fallSpeed = 0;
        }
        wasOnGround = in.onGround;

        // ---------------- базовое состояние (Core)
        double v = in.speed;
        if (in.dead) {
            want("idle", 1, 0.2);
        } else if (in.sleeping) {
            want("sleep", 1, 0.3);
        } else if (in.riding) {
            want("sit", 1, 0.5);
        } else if (in.fallFlying || in.spinAttack) {
            want("glide", 1, 0.3);
        } else if (in.swimming || (in.inWater && !in.onGround)) {
            if (v > 1.0 || in.swimming) {
                Slot s = want("swim", 1, 0.4);
                s.mirror = false;
                AnimationTrack t = s.track;
                if (t != null) t.adjustSpeed(clamp(v / 4.0, 0.4, 2.0));
            } else {
                want("swim_idle", 1, 0.4);
            }
        } else if (in.climbing) {
            Slot s = want("climb", 1, 0.1);
            if (s.track != null) s.track.adjustSpeed(Math.abs(in.vy) < 0.3 ? 0 : clamp(in.vy / 2.35, -2, 2));
        } else if (air && airTime > 0.12) {
            boolean jumping = jumpTime >= 0 && jumpTime < 0.31 && in.vy > -2;
            if (jumping) want("jump", 1, 0.1);
            else want("fall", 1, 0.2);
        } else if (air && jumpTime >= 0) {
            want("jump", 1, 0.1);
        } else {
            ground(in, v, dt);
        }

        // ---------------- движения мода (Movement)
        if (in.chargeWeight > 1.0E-3) {
            Slot s = want("charge", in.chargeWeight, 0.001);
            s.driven = true;
            if (s.track != null && s.track.isPlaying()) s.track.scrubTo(in.chargeAge, 1);
        }
        if (in.leapAge >= 0) {
            double a = in.leapAge * 20;
            double outW = Math.max(smooth((a - 14) / 10), in.flightW > 0.5 ? smooth((a - 8) / 6) : 0);
            double w = smooth(a / 1.5) * (1 - outW);
            Slot s = want("leap", w, 0.001);
            s.driven = true;
            if (s.track != null && s.track.isPlaying()) s.track.scrubTo(in.leapAge, 1);
        }
        if (in.flightW > 1.0E-3) {
            double fw = in.flightW, land = in.landW, move = in.flightMove, b = in.boostW * move;
            want("fly_hover", fw * (1 - move) * (1 - land), 0.001);
            want("fly_glide", fw * move * (1 - b) * (1 - land), 0.001);
            want("fly_boost", fw * b * (1 - land), 0.001);
            want("fly_land", fw * land, 0.001);
        }
        if (in.dashKind >= 0 && (in.dashKind != lastDashKind || in.dashAge < lastDashAge)) {
            String name = switch (in.dashKind) {
                case 1 -> "dash_back";
                case 2 -> "dash_left";
                case 3 -> "dash_right";
                default -> "dash_front";
            };
            fire(name, 0.06, 1, 1, false);
        }
        lastDashKind = in.dashKind;
        lastDashAge = in.dashAge;

        // ---------------- руки: предметы, взмахи, использование
        boolean rightItem = in.leftHanded ? in.offHandItem : in.mainHandItem;
        boolean leftItem = in.leftHanded ? in.mainHandItem : in.offHandItem;
        boolean using = in.use != AnimateInput.Use.NONE;
        boolean useRight = using && (in.useMain != in.leftHanded);
        if (rightItem && !(using && useRight)) want("hold_item", 1, 0.15).mirror = false;
        // левая рука — та же анимация зеркально, своим треком (держать можно обеими руками сразу)
        if (leftItem && !(using && !useRight)) want("hold_item_left", "hold_item", 1, 0.15).mirror = true;
        if (in.crossbowCharged && !using) want("hold_crossbow", 1, 0.15).mirror = in.crossbowMain == in.leftHanded;
        if (using) {
            String name = switch (in.use) {
                case EAT -> "use_eat";
                case DRINK -> clip("use_drink") != null ? "use_drink" : "use_eat";
                case BLOCK -> "use_block";
                case BOW -> "use_bow";
                case SPEAR -> "use_spear";
                case CROSSBOW -> "use_crossbow";
                case SPYGLASS -> "use_spyglass";
                case HORN -> "use_horn";
                case BRUSH -> "use_brush";
                default -> null;
            };
            if (name != null) want(name, 1, 0.12).mirror = !useRight;
        }
        if (in.swingStarted) {
            boolean right = in.swingMain != in.leftHanded;
            fire("swing", 0.04, 1, 1, !right);
        }

        // ---------------- наклоны от поворотов и разгона (на земле)
        double groundish = in.onGround && in.flightW < 0.5 ? 1 : 0;
        double moving = smooth(v / 3.0);
        // в поворот наклоняются (поворот вправо — крен вправо), при разгоне — вперёд
        double tRoll = clamp(in.turnRate / 220.0, -1, 1) * 8.0 * moving * groundish;
        double tPitch = clamp(in.accel / 40.0, -1, 1) * 5.0 * groundish;
        // ---------------- полёт: корпус ложится по ходу (сильнее на ускорении), вверх — откидывается,
        // вниз — клонится; в сторону и в поворот — крен, как у самолёта
        double fw = clamp(in.flightW, 0, 1) * (1 - clamp(in.landW, 0, 1));
        if (fw > 1.0E-3) {
            double move = clamp(in.flightMove, 0, 1);
            double fp = clamp(14 + 16 * in.boostW - 0.35 * in.elevation, -25, 45) * move;
            double fr = clamp(in.bank * 18 + clamp(in.turnRate / 220.0, -1, 1) * 14 * move, -30, 30);
            tPitch += fp * fw;
            tRoll += fr * fw;
        }
        leanRoll = approach(leanRoll, tRoll, fw > 1.0E-3 ? 5 : 7, dt);
        leanPitch = approach(leanPitch, tPitch, fw > 1.0E-3 ? 5 : 7, dt);
        leanPivot = approach(leanPivot, 12 * clamp(in.flightW, 0, 1), 6, dt);

        applySlots(dt);
    }

    /** Земля: стойка и шаги со смешиванием по скорости. */
    private void ground(AnimateInput in, double v, double dt) {
        double fade = 0.2;
        if (in.crouching) {
            double move = smooth((v - 0.1) / 0.6);
            want("crouch_idle", 1 - move, fade);
            Slot w = want("crouch_walk", move, fade);
            w.driven = true;
            advancePhase(in, v, dt, new String[]{"crouch_walk"}, new double[]{1});
            drive(w);
            return;
        }
        // веса: стойка, ходьба, бег (спринт MC), сверхбег мода
        double tIdle = 1 - smooth((v - 0.15) / 0.85);
        double tWalk = tri(v, 0.15, 1.0, 4.7, 5.3);
        double tRun = tri(v, 4.7, 5.3, 8.5, 10.5);
        double tSprint = smooth((v - 8.5) / 2.0);
        double sum = tIdle + tWalk + tRun + tSprint;
        if (sum < 1.0E-6) {
            tIdle = 1;
            sum = 1;
        }
        tIdle /= sum;
        tWalk /= sum;
        tRun /= sum;
        tSprint /= sum;

        // стойка: иногда другая (как два варианта idle в Roblox)
        idleSwitch -= dt;
        if (idleSwitch <= 0) {
            idleVariant = !idleVariant && random.nextInt(4) == 0 && clip("idle_look") != null;
            idleSwitch = idleVariant ? 3.5 : 6 + random.nextDouble() * 6;
        }
        want(idleVariant ? "idle_look" : "idle", tIdle, 0.3);

        Slot w = want("walk", tWalk, fade), r = want("run", tRun, fade), s = want("sprint", tSprint, fade);
        w.driven = r.driven = s.driven = true;
        advancePhase(in, v, dt, new String[]{"walk", "run", "sprint"}, new double[]{tWalk, tRun, tSprint});
        drive(w);
        drive(r);
        drive(s);
    }

    /** Общая фаза шагов: за цикл персонаж проходит длину шага выбранной смеси циклов. */
    private void advancePhase(AnimateInput in, double v, double dt, String[] names, double[] weights) {
        double stride = 0, wsum = 0;
        for (int i = 0; i < names.length; i++) {
            Clip c = clip(names[i]);
            if (c == null || weights[i] <= 0 || c.length <= 0) continue;
            double ms = c.moveSpeed > 0 ? c.moveSpeed : 4.3;
            stride += weights[i] * ms * c.length;
            wsum += weights[i];
        }
        if (wsum <= 0) return;
        stride /= wsum;
        // на малой скорости шаг укорачивается (не семенить на месте)
        double rate = v / Math.max(0.3, stride) * smooth(v / 0.4);
        phaseDir = in.forward < -0.25 ? -1 : 1;
        phase += dt * rate * phaseDir;
        phase -= Math.floor(phase);
    }

    /** Поставить цикл шага на общую фазу; маркеры (шаги) между прошлым и новым временем срабатывают. */
    private void drive(Slot s) {
        if (s.track != null && s.track.isPlaying() && s.track.clip.length > 0) {
            s.track.setFrozen(true);
            s.track.scrubTo(phase * s.track.clip.length, phaseDir);
        }
    }

    // ------------------------------------------------------------------ математика

    static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    static double smooth(double x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    static double tri(double v, double a, double b, double c, double d) {
        if (v <= a || v >= d) return 0;
        if (v < b) return smooth((v - a) / (b - a));
        if (v <= c) return 1;
        return 1 - smooth((v - c) / (d - c));
    }

    static double approach(double cur, double target, double rate, double dt) {
        return target + (cur - target) * Math.exp(-rate * dt);
    }
}
