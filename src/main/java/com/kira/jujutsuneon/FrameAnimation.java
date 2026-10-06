package com.kira.jujutsuneon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Покадровая анимация: полная поза модели на каждый кадр (fps кадров в секунду, по умолчанию 24).
 * Хранится плотно — values[кадр][канал] во внутренних единицах (радианы / блоки / пиксели);
 * NaN — канал в этом кадре не задан (берётся ванильное значение / нижний слой).
 *
 * Проигрыватель (FrameAnimationPlayer) берёт позу в любой момент времени с точностью до миллисекунды:
 * между соседними кадрами — сглаживание Кэтмелла–Рома (или линейно / ступенькой), поэтому
 * анимация плавная при любом FPS игры, а каждый кадр файла проходится точно.
 */
final class FrameAnimation {

    enum Interp { CATMULL, LINEAR, STEP }

    enum HeadMode { ABSOLUTE, ADDITIVE }

    enum FirstPerson { NONE, VANILLA, MODEL }

    static final class Event {
        final int frame;
        final String name;
        final String data;

        Event(int frame, String name, String data) {
            this.frame = frame;
            this.name = name;
            this.data = data == null ? "" : data;
        }
    }

    /** Живой слой — настройки вторичного движения (по умолчанию выключен). */
    static final class Secondary {
        boolean enabled;
        /** Общая сила (0..1+). */
        float amount = 1f;
        /** Пружины: жёсткость (1/с²) и демпфирование (1/с). Меньше демпфирование — больше захлёст. */
        float stiffness = 140f;
        float damping = 13f;
        /** Сила пружин по частям тела (0 — часть следует анимации точно). */
        float arms = 1f, legs = 0.4f, torso = 0.6f, head = 0.7f, body = 0.3f;
        /** Дыхание: амплитуда (1 = естественная) и период (с). */
        float breathing = 0f;
        float breathPeriod = 3.6f;
        /** Мелкие живые колебания головы и рук (шум), 1 = естественно. */
        float micro = 0f;
        /** Отдача корпуса и рук от собственного движения игрока (ускорения тела). */
        float inertia = 0f;
    }

    final String name;
    final String source;
    final float fps;
    final int frameCount;
    final float[][] values;
    final boolean[] used;
    final boolean loop;
    final int loopFrom;
    final boolean holdLast;
    final Interp interp;
    /** Плавный вход и выход, секунды. */
    final float fadeIn;
    final float fadeOut;
    final float speed;
    final HeadMode headMode;
    final FirstPerson firstPerson;
    final boolean fpRightArm;
    final boolean fpLeftArm;
    /** До какого кадра действует режим первого лица (-1 — всю анимацию). */
    final int fpUntil;
    /** Часы: игровые тики (по умолчанию) или реальное время (не зависит от лагов сервера). */
    final boolean realTime;
    final List<Event> events;
    final Secondary secondary;

    FrameAnimation(String name, String source, float fps, int frameCount, float[][] values, boolean loop, int loopFrom,
                   boolean holdLast, Interp interp, float fadeIn, float fadeOut, float speed, HeadMode headMode,
                   FirstPerson firstPerson, boolean fpRightArm, boolean fpLeftArm, int fpUntil, boolean realTime,
                   List<Event> events, Secondary secondary) {
        this.name = name;
        this.source = source;
        this.fps = Math.max(1f, fps);
        this.frameCount = Math.max(1, frameCount);
        this.values = values;
        this.loop = loop;
        this.loopFrom = Math.max(0, Math.min(this.frameCount - 1, loopFrom));
        this.holdLast = holdLast;
        this.interp = interp;
        this.fadeIn = Math.max(0f, fadeIn);
        this.fadeOut = Math.max(0f, fadeOut);
        this.speed = speed <= 0f ? 1f : speed;
        this.headMode = headMode;
        this.firstPerson = firstPerson;
        this.fpRightArm = fpRightArm;
        this.fpLeftArm = fpLeftArm;
        this.fpUntil = fpUntil;
        this.realTime = realTime;
        List<Event> ev = new ArrayList<>(events);
        ev.sort((a, b) -> Integer.compare(a.frame, b.frame));
        this.events = Collections.unmodifiableList(ev);
        this.secondary = secondary == null ? new Secondary() : secondary;
        this.used = new boolean[FrameChannel.COUNT];
        for (float[] f : values) {
            for (int c = 0; c < FrameChannel.COUNT; c++) {
                if (!Float.isNaN(f[c])) used[c] = true;
            }
        }
    }

    /** Длительность в секундах (без затухания). */
    float duration() {
        return frameCount / fps;
    }

    boolean usesPart(String part) {
        for (FrameChannel c : FrameChannel.ALL) {
            if (used[c.ordinal()] && c.part.equals(part)) return true;
        }
        return false;
    }

    /** Номер кадра (дробный) для момента t секунд от начала с учётом петли. */
    double frameAt(double t) {
        double f = t * fps;
        if (loop && frameCount > 1) {
            if (f >= frameCount) {
                double span = frameCount - loopFrom;
                f = loopFrom + ((f - loopFrom) % span);
            }
        } else {
            f = Math.min(f, frameCount - 1);
        }
        return Math.max(0.0, f);
    }

    /** Значение канала в кадре i (с учётом петли и краёв). */
    private float at(int i, int c) {
        if (loop && frameCount > 1) {
            if (i >= frameCount) i = loopFrom + (i - loopFrom) % (frameCount - loopFrom);
            if (i < 0) i = 0;
        } else {
            i = Math.max(0, Math.min(frameCount - 1, i));
        }
        return values[i][c];
    }

    /**
     * Поза в дробном кадре f: out[канал] во внутренних единицах, NaN — не задан.
     */
    void sample(double f, float[] out) {
        int i = (int) Math.floor(f);
        float u = (float) (f - i);
        for (int c = 0; c < FrameChannel.COUNT; c++) {
            if (!used[c]) {
                out[c] = Float.NaN;
                continue;
            }
            float p1 = at(i, c);
            float p2 = at(i + 1, c);
            if (Float.isNaN(p1) && Float.isNaN(p2)) {
                out[c] = Float.NaN;
                continue;
            }
            if (Float.isNaN(p1)) {
                out[c] = u >= 0.5f ? p2 : Float.NaN;
                continue;
            }
            if (Float.isNaN(p2) || u <= 0f || interp == Interp.STEP) {
                out[c] = p1;
                continue;
            }
            if (interp == Interp.LINEAR) {
                out[c] = p1 + (p2 - p1) * u;
                continue;
            }
            float p0 = at(i - 1, c);
            float p3 = at(i + 2, c);
            if (Float.isNaN(p0)) p0 = p1;
            if (Float.isNaN(p3)) p3 = p2;
            out[c] = catmull(p0, p1, p2, p3, u);
        }
    }

    /** Кэтмелл–Ром: проходит точно через каждый кадр, между кадрами — гладко. */
    static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    /** Список заданных каналов (для отладки и экспорта). */
    List<FrameChannel> usedChannels() {
        List<FrameChannel> list = new ArrayList<>();
        for (FrameChannel c : FrameChannel.ALL) {
            if (used[c.ordinal()]) list.add(c);
        }
        return list;
    }
}
