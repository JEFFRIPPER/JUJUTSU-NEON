package com.kira.jujutsuneon.rbx;

import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.keyframe.BoneAnimation;
import software.bernie.geckolib.core.keyframe.event.data.CustomInstructionKeyframeData;
import software.bernie.geckolib.core.keyframe.event.data.ParticleKeyframeData;
import software.bernie.geckolib.core.keyframe.event.data.SoundKeyframeData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Анимация из Blockbench (как Animation-ассет в Roblox): запечённая GeckoLib-анимация + свойства.
 *
 * Свойства задаются прямо в Blockbench — инструкцией на дорожке Timeline (эффекты), в любом месте анимации:
 * <pre>
 *   @priority=action   — Core / Idle / Movement / Action / Action2..4 (по умолчанию Action)
 *   @fade_in=0.1       — плавный вход, секунды (по умолчанию 0.1, как в Roblox)
 *   @fade_out=0.15     — плавный выход по окончании
 *   @look=0.3          — насколько голова следит за взглядом (0..1; по умолчанию 1 для Core/Idle, 0.25 для остальных)
 *   @aim=1             — плечи анимации поворачиваются за взглядом (лук, щит, прицел)
 *   @face=1            — тело разворачивается туда, куда смотрит игрок (0..1; по умолчанию 1 для Action и выше)
 *   @fp=arms           — от первого лица видны руки модели: arms / right / left (по умолчанию для Action — arms)
 *   @fp=none           — от первого лица — ванильные руки
 *   @blend=additive    — анимация прибавляется к нижним слоям, а не заменяет их
 *   @move_speed=4.3    — для циклов шага: скорость (блоки/с), под которую нарисован цикл (шаг не скользит)
 * </pre>
 * Остальные инструкции — маркеры (как KeyframeMarker в Roblox): {@code hit} или {@code hit=3}. Их ловят
 * {@link AnimationTrack#markerReached} и {@link AnimationTrack#getMarkerReachedSignal(String)}.
 */
public final class Clip {

    public enum FirstPerson {NONE, ARMS, RIGHT, LEFT}

    public record Marker(double time, String name, String param) {
    }

    public record SoundCue(double time, String sound) {
    }

    public record ParticleCue(double time, String effect, String locator) {
    }

    /** Короткое имя (как в файле), в нижнем регистре. */
    public final String name;
    /** Файл, откуда загружена (ресурс мода или путь в папке config). */
    public final String source;
    public final Animation animation;
    /** Длина, секунды. */
    public final double length;
    public final boolean looped;
    public final boolean holdLastFrame;
    public final AnimationPriority priority;
    public final double fadeIn, fadeOut;
    public final double look, aim;
    /** Насколько быстро тело доворачивается к взгляду, пока играет анимация (0 — не трогать). */
    public final double face;
    public final FirstPerson firstPerson;
    public final boolean additive;
    /** Скорость, под которую нарисован цикл шага, блоки/с (0 — не цикл шага). */
    public final double moveSpeed;
    public final Marker[] markers;
    public final SoundCue[] sounds;
    public final ParticleCue[] particles;
    /** Кости, у которых есть ключи (для подсказок и отладки). */
    public final String[] bones;

    Clip(String name, String source, Animation animation) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.source = source;
        this.animation = animation;
        double len = animation.length();
        if (!(len > 0) || len > 1.0E8) len = 0;
        this.length = len / 20.0;
        String loop = loopId(animation);
        this.looped = "loop".equals(loop) || "true".equals(loop);
        this.holdLastFrame = "hold_on_last_frame".equals(loop);

        AnimationPriority pr = AnimationPriority.ACTION;
        double fi = 0.1, fo = 0.15, lk = Double.NaN, am = 0, fc = Double.NaN;
        FirstPerson fp = null;
        boolean add = false;
        double ms = 0;
        List<Marker> marks = new ArrayList<>();
        for (CustomInstructionKeyframeData d : animation.keyFrames().customInstructions()) {
            double t = d.getStartTick() / 20.0;
            for (String raw : splitInstructions(d.getInstructions())) {
                if (raw.startsWith("@")) {
                    String[] kv = raw.substring(1).split("[=:]", 2);
                    String k = kv[0].trim().toLowerCase(Locale.ROOT);
                    String v = kv.length > 1 ? kv[1].trim() : "";
                    switch (k) {
                        case "priority" -> pr = AnimationPriority.parse(v, pr);
                        case "fade_in", "fadein" -> fi = num(v, fi);
                        case "fade_out", "fadeout" -> fo = num(v, fo);
                        case "look" -> lk = num(v, 1);
                        case "aim" -> am = v.isEmpty() ? 1 : num(v, 1);
                        case "face" -> fc = v.isEmpty() ? 1 : num(v, 1);
                        case "fp", "first_person" -> fp = parseFp(v);
                        case "blend" -> add = v.equalsIgnoreCase("additive") || v.equalsIgnoreCase("add");
                        case "move_speed", "movespeed" -> ms = num(v, 0);
                        default -> {
                        }
                    }
                    continue;
                }
                String[] kv = raw.split("[=:]", 2);
                marks.add(new Marker(t, kv[0].trim(), kv.length > 1 ? kv[1].trim() : ""));
            }
        }
        marks.sort(Comparator.comparingDouble(Marker::time));
        this.markers = marks.toArray(new Marker[0]);
        this.priority = pr;
        this.fadeIn = Math.max(0, fi);
        this.fadeOut = Math.max(0, fo);
        boolean base = pr == AnimationPriority.CORE || pr == AnimationPriority.IDLE;
        this.look = Double.isNaN(lk) ? (base ? 1 : 0.25) : clamp01(lk);
        this.aim = clamp01(am);
        this.face = Double.isNaN(fc) ? (pr.ordinal() >= AnimationPriority.ACTION.ordinal() ? 1 : 0) : clamp01(fc);
        this.firstPerson = fp != null ? fp : (pr.ordinal() >= AnimationPriority.ACTION.ordinal() ? FirstPerson.ARMS : FirstPerson.NONE);
        this.additive = add;
        this.moveSpeed = Math.max(0, ms);

        List<SoundCue> snd = new ArrayList<>();
        for (SoundKeyframeData s : animation.keyFrames().sounds()) {
            snd.add(new SoundCue(s.getStartTick() / 20.0, s.getSound()));
        }
        snd.sort(Comparator.comparingDouble(SoundCue::time));
        this.sounds = snd.toArray(new SoundCue[0]);
        List<ParticleCue> par = new ArrayList<>();
        for (ParticleKeyframeData p : animation.keyFrames().particles()) {
            par.add(new ParticleCue(p.getStartTick() / 20.0, p.getEffect(), p.getLocator()));
        }
        par.sort(Comparator.comparingDouble(ParticleCue::time));
        this.particles = par.toArray(new ParticleCue[0]);
        this.bones = Arrays.stream(animation.boneAnimations()).map(BoneAnimation::boneName).toArray(String[]::new);
    }

    private static String loopId(Animation a) {
        try {
            return a.loopType().getId();
        } catch (IllegalStateException e) {
            return "play_once";
        }
    }

    /** GeckoLib отдаёт инструкции строкой; массив из Blockbench превращается в "[a, b]". */
    static List<String> splitInstructions(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        for (String part : t.split("[;,\\n\\r]")) {
            String p = part.trim();
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) p = p.substring(1, p.length() - 1).trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static FirstPerson parseFp(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "arms", "both", "model", "true", "1" -> FirstPerson.ARMS;
            case "right" -> FirstPerson.RIGHT;
            case "left" -> FirstPerson.LEFT;
            default -> FirstPerson.NONE;
        };
    }

    private static double num(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private int[] boneIdx;

    /** Номера костей ({@link Bones}) в порядке animation.boneAnimations(). */
    int[] boneIndices() {
        int[] b = boneIdx;
        if (b == null) {
            b = new int[bones.length];
            for (int i = 0; i < b.length; i++) b[i] = Bones.index(bones[i]);
            boneIdx = b;
        }
        return b;
    }

    public boolean animatesBone(String bone) {
        for (String b : bones) {
            if (b.equals(bone)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return name + " (" + source + ")";
    }
}
