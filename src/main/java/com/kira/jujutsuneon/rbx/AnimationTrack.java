package com.kira.jujutsuneon.rbx;

import java.util.HashMap;
import java.util.Map;

/**
 * Проигрывание одной анимации на {@link Animator} — как AnimationTrack в Roblox.
 *
 * <pre>
 *   AnimationTrack t = animator.loadAnimation("red_cast");
 *   t.play();                       // fadeTime 0.1, weight 1, speed 1
 *   t.play(0.2, 1, 1.5);
 *   t.getMarkerReachedSignal("hit").connect(param -> ...);
 *   t.adjustSpeed(0.5); t.adjustWeight(0.3, 0.2);
 *   t.stop(0.3);
 * </pre>
 */
public final class AnimationTrack {

    public final Animator animator;
    public final Clip clip;

    private AnimationPriority priority;
    private boolean looped;
    private boolean mirror;
    private double time;
    private double speed = 1;
    private boolean playing;
    private boolean active;
    // вес: текущий, цель и линейный переход
    private double weight;
    private double weightTarget = 1;
    private double fadeFrom, fadeTo, fadeTime, fadeElapsed;
    private boolean fadingToStop;
    /** Только что запущен: маркеры на самом начале анимации тоже срабатывают. */
    private boolean fresh;
    /** Для отладки и стоп-кадров: время не идёт. */
    private boolean frozen;
    /** Дойдя до конца, не останавливаться, а держать последний кадр (для скриптов вроде Animate). */
    private boolean holdAtEnd;
    /** Вид от первого лица для этого трека (null — как в клипе) и до какой секунды анимации. */
    private Clip.FirstPerson fpMode;
    private double fpUntil = Double.POSITIVE_INFINITY;
    /** Сетевой: трек пришёл с сервера/другого клиента (не пересылать обратно). */
    boolean remote;
    /** Когда запущен (часы аниматора) — чтобы не запускать дважды одно и то же из разных источников. */
    double startedAt = -1;
    long serial;

    /** Сработал маркер (имя, параметр) — как KeyframeReached / MarkerReached в Roblox. */
    public final Signal<Clip.Marker> markerReached = new Signal<>();
    /** Трек остановлен (вызван stop или дошёл до конца). */
    public final Signal<AnimationTrack> stopped = new Signal<>();
    /** Трек полностью закончился (вес ушёл в 0). */
    public final Signal<AnimationTrack> ended = new Signal<>();
    /** Зацикленный трек начал новый круг. */
    public final Signal<AnimationTrack> didLoop = new Signal<>();
    private Map<String, Signal<String>> markerSignals;

    AnimationTrack(Animator animator, Clip clip) {
        this.animator = animator;
        this.clip = clip;
        this.priority = clip.priority;
        this.looped = clip.looped;
    }

    // ------------------------------------------------------------------ как в Roblox

    public void play() {
        play(clip.fadeIn, 1, 1);
    }

    public void play(double fadeTime) {
        play(fadeTime, 1, 1);
    }

    /** Запустить с начала (если уже играет — тоже с начала; вес плавно идёт от текущего к weight). */
    public void play(double fadeTime, double weight, double speed) {
        this.speed = speed;
        this.time = speed < 0 ? clip.length : 0;
        this.playing = true;
        this.fadingToStop = false;
        this.frozen = false;
        this.fresh = true;
        this.weightTarget = weight;
        this.startedAt = animator.clock();
        this.serial = animator.nextSerial();
        fadeWeight(weight, fadeTime);
        animator.activate(this);
        animator.animationPlayed.fire(this);
    }

    public void stop() {
        stop(clip.fadeOut);
    }

    /** Плавно остановить за fadeTime секунд. */
    public void stop(double fadeTime) {
        if (!active) return;
        boolean wasPlaying = playing;
        playing = false;
        fadingToStop = true;
        fadeWeight(0, fadeTime);
        if (wasPlaying) stopped.fire(this);
        if (fadeTime <= 0) finish();
    }

    public void adjustSpeed(double speed) {
        this.speed = speed;
    }

    public void adjustWeight(double weight) {
        adjustWeight(weight, 0.1);
    }

    public void adjustWeight(double weight, double fadeTime) {
        weightTarget = weight;
        if (!fadingToStop) fadeWeight(weight, fadeTime);
    }

    public Signal<String> getMarkerReachedSignal(String name) {
        if (markerSignals == null) markerSignals = new HashMap<>();
        return markerSignals.computeIfAbsent(name, k -> new Signal<>());
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Ещё влияет на позу (играет или плавно уходит). */
    public boolean isActive() {
        return active;
    }

    public double getTimePosition() {
        return time;
    }

    public void setTimePosition(double t) {
        time = Math.max(0, Math.min(t, clip.length));
    }

    public double getLength() {
        return clip.length;
    }

    public double getSpeed() {
        return speed;
    }

    public double getWeightCurrent() {
        return weight;
    }

    public double getWeightTarget() {
        return weightTarget;
    }

    public AnimationPriority getPriority() {
        return priority;
    }

    public void setPriority(AnimationPriority p) {
        priority = p;
    }

    public boolean isLooped() {
        return looped;
    }

    public void setLooped(boolean l) {
        looped = l;
    }

    public boolean isMirrored() {
        return mirror;
    }

    /** Зеркально: правые кости играют левые ключи и наоборот (своё расширение, в Roblox нет). */
    public void setMirrored(boolean m) {
        mirror = m;
    }

    /** Стоп-кадр: время не идёт (для /jnanim pose). */
    public void setFrozen(boolean f) {
        frozen = f;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** Неповторяемая анимация не останавливается в конце, а держит последний кадр (пока её не остановят). */
    public void setHoldAtEnd(boolean h) {
        holdAtEnd = h;
    }

    /**
     * Перевести время (для треков, которыми управляет скрипт: фаза шага, зарядка) так, чтобы сработали
     * маркеры между прошлым и новым временем. dir — направление хода (+1/−1): у зацикленной анимации
     * переход через конец круга считается движением в эту сторону.
     */
    public void scrubTo(double t, int dir) {
        double len = clip.length;
        if (len <= 0) {
            time = 0;
            return;
        }
        double prev = time;
        if (fresh) {
            prev = dir >= 0 ? -1.0E-9 : len + 1.0E-9;
            fresh = false;
        }
        if (looped) {
            t = ((t % len) + len) % len;
            if (dir >= 0 && t < prev) {
                fireMarkers(prev, len + 1.0E-9);
                prev = -1.0E-9;
                didLoop.fire(this);
            } else if (dir < 0 && t > prev) {
                fireMarkers(prev, -1.0E-9);
                prev = len + 1.0E-9;
                didLoop.fire(this);
            }
        } else {
            t = Math.max(0, Math.min(t, len));
        }
        fireMarkers(prev, t);
        time = t;
    }

    public String getName() {
        return clip.name;
    }

    /** Руки модели от первого лица: режим (null — как в клипе) и до какой секунды анимации (∞ — всю). */
    public void setFirstPerson(Clip.FirstPerson mode, double untilSeconds) {
        fpMode = mode;
        fpUntil = untilSeconds;
    }

    public Clip.FirstPerson firstPerson() {
        if (time > fpUntil) return Clip.FirstPerson.NONE;
        return fpMode != null ? fpMode : clip.firstPerson;
    }

    // ------------------------------------------------------------------ шаг

    private void fadeWeight(double to, double fadeTime) {
        fadeFrom = weight;
        fadeTo = to;
        fadeTime = Math.max(0, fadeTime);
        this.fadeTime = fadeTime;
        fadeElapsed = 0;
        if (fadeTime <= 0) weight = to;
    }

    void markActive(boolean a) {
        active = a;
    }

    /** Продвинуть на dt секунд: вес, время, маркеры. Возвращает false, когда трек закончился. */
    boolean step(double dt) {
        if (!active) return false;
        // вес
        if (fadeTime > 0 && fadeElapsed < fadeTime) {
            fadeElapsed = Math.min(fadeTime, fadeElapsed + dt);
            weight = fadeFrom + (fadeTo - fadeFrom) * (fadeElapsed / fadeTime);
        } else {
            weight = fadeTo;
        }
        if (fadingToStop && weight <= 1.0E-4) {
            finish();
            return false;
        }
        if (frozen || dt <= 0) return true;
        // время
        double len = clip.length;
        double prev = time;
        if (fresh) {
            prev = speed >= 0 ? -1.0E-9 : len + 1.0E-9;
            fresh = false;
        }
        double next = time + dt * speed;
        if (len <= 0) {
            time = 0;
            if (playing && !looped && !clip.holdLastFrame && !holdAtEnd) endReached();
            return active;
        }
        if (looped) {
            if (next >= len || next < 0) {
                fireMarkers(prev, speed >= 0 ? len + 1.0E-9 : -1.0E-9);
                next = ((next % len) + len) % len;
                prev = speed >= 0 ? -1.0E-9 : len + 1.0E-9;
                didLoop.fire(this);
            }
            fireMarkers(prev, next);
            time = next;
            return true;
        }
        if (next >= len || next <= 0 && speed < 0) {
            next = speed >= 0 ? len : 0;
            fireMarkers(prev, speed >= 0 ? len + 1.0E-9 : -1.0E-9);
            time = next;
            if (playing && !clip.holdLastFrame && !holdAtEnd) endReached();
            return active;
        }
        fireMarkers(prev, next);
        time = next;
        return true;
    }

    private void endReached() {
        playing = false;
        fadingToStop = true;
        fadeWeight(0, clip.fadeOut);
        stopped.fire(this);
        if (clip.fadeOut <= 0) finish();
    }

    private void finish() {
        weight = 0;
        playing = false;
        if (active) {
            active = false;
            animator.deactivate(this);
            ended.fire(this);
        }
    }

    /** Маркеры, звуки и частицы, попавшие в (a, b] (или [b, a) при обратном ходе). */
    private void fireMarkers(double a, double b) {
        if (a == b) return;
        double lo = Math.min(a, b), hi = Math.max(a, b);
        boolean fwd = b > a;
        for (Clip.Marker m : clip.markers) {
            if (fwd ? (m.time() > lo && m.time() <= hi) : (m.time() >= lo && m.time() < hi)) {
                markerReached.fire(m);
                if (markerSignals != null) {
                    Signal<String> s = markerSignals.get(m.name());
                    if (s != null) s.fire(m.param());
                }
                animator.markerReached.fire(new Animator.MarkerEvent(this, m));
            }
        }
        for (Clip.SoundCue s : clip.sounds) {
            if (fwd ? (s.time() > lo && s.time() <= hi) : (s.time() >= lo && s.time() < hi)) {
                animator.soundCue.fire(new Animator.SoundEvent(this, s));
            }
        }
        for (Clip.ParticleCue p : clip.particles) {
            if (fwd ? (p.time() > lo && p.time() <= hi) : (p.time() >= lo && p.time() < hi)) {
                animator.particleCue.fire(new Animator.ParticleEvent(this, p));
            }
        }
    }

    @Override
    public String toString() {
        return clip.name + "[" + priority + (playing ? " playing" : active ? " fading" : " stopped")
                + String.format(" t=%.2f/%.2f w=%.2f s=%.2f]", time, clip.length, weight, speed);
    }
}
