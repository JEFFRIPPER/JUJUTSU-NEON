package com.kira.jujutsuneon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Загрузка покадровых анимаций. Понимает четыре формата (определяется по содержимому):
 *
 * 1. "jn_frames" — плотный покадровый: "channels" + "frames" (каждый кадр — массив значений
 *    в порядке channels, или объект {"rightArm.pitch": -90, ...}; в объекте незаданный канал
 *    держит значение прошлого кадра).
 * 2. "jn_keys" — ключи с кривыми: "tracks" {канал: [[кадр, значение, кривая?], ...]} и/или
 *    "keys" [{"frame": 5, "ease": "outquad", "pose": {канал: значение}}]. Кривая ключа — переход
 *    В этот ключ от предыдущего. При загрузке всё запекается в каждый кадр.
 * 3. Анимации Blockbench / Bedrock ("animations" → "bones"): кости head, body (корпус),
 *    rightArm/right_arm, leftArm, rightLeg, leftLeg, root/waist (всё тело). Знаки осей настраиваются
 *    полями "jn_rotation_sign" и "jn_position_sign" (по умолчанию как в GeckoLib: X и Y поворота инвертированы).
 * 4. Эмоции Player Animator (наши player_animation/*.json, "emote" → "moves") — переводятся в кадры
 *    точно так же, как их проигрывает Player Animator (кривая предыдущего ключа, затухание до stopTick).
 */
final class FrameAnimationLoader {

    private FrameAnimationLoader() {
    }

    static final class Builder {
        String name;
        String source = "";
        float fps = 24f;
        int frames;
        float[][] values;
        boolean loop;
        int loopFrom;
        boolean holdLast;
        FrameAnimation.Interp interp = FrameAnimation.Interp.CATMULL;
        float fadeIn = 0.1f;
        float fadeOut = 0.15f;
        float speed = 1f;
        FrameAnimation.HeadMode headMode = FrameAnimation.HeadMode.ABSOLUTE;
        FrameAnimation.FirstPerson firstPerson = FrameAnimation.FirstPerson.NONE;
        boolean fpRight = true, fpLeft = true;
        int fpUntil = -1;
        boolean realTime;
        final List<FrameAnimation.Event> events = new ArrayList<>();
        FrameAnimation.Secondary secondary = new FrameAnimation.Secondary();

        void alloc(int count) {
            frames = Math.max(1, count);
            values = new float[frames][FrameChannel.COUNT];
            for (float[] f : values) Arrays.fill(f, Float.NaN);
        }

        void set(int frame, FrameChannel c, float internal) {
            if (frame < 0 || frame >= frames || c == null) return;
            values[frame][c.ordinal()] = internal;
        }

        FrameAnimation build() {
            return new FrameAnimation(name, source, fps, frames, values, loop, loopFrom, holdLast, interp, fadeIn, fadeOut,
                    speed, headMode, firstPerson, fpRight, fpLeft, fpUntil, realTime, events, secondary);
        }
    }

    /** Разобрать файл. defaultName — имя файла без .json. Может вернуть несколько анимаций (Blockbench). */
    static List<FrameAnimation> parse(String defaultName, String source, JsonElement root) {
        List<FrameAnimation> out = new ArrayList<>();
        if (root == null || !root.isJsonObject()) throw new IllegalArgumentException("ожидался JSON-объект");
        JsonObject o = root.getAsJsonObject();
        String format = str(o, "format", "");
        if (o.has("animations") && o.get("animations").isJsonObject() && !"jn_frames".equals(format) && !"jn_keys".equals(format)) {
            out.addAll(parseBedrock(defaultName, source, o));
        } else if (o.has("emote") && o.get("emote").isJsonObject()) {
            out.add(parseEmote(defaultName, source, o));
        } else if (o.has("frames") || "jn_frames".equals(format)) {
            out.add(parseFrames(defaultName, source, o));
        } else if (o.has("tracks") || o.has("keys") || "jn_keys".equals(format)) {
            out.add(parseKeys(defaultName, source, o));
        } else {
            throw new IllegalArgumentException("неизвестный формат (нужны frames, tracks/keys, animations или emote)");
        }
        return out;
    }

    // ------------------------------------------------------------------ общие поля

    private static void common(Builder b, JsonObject o, String defaultName, String source) {
        b.name = str(o, "name", defaultName);
        b.source = source;
        b.fps = (float) num(o, "fps", 24.0);
        b.loop = bool(o, "loop", false);
        b.loopFrom = (int) num(o, "loop_from", 0);
        b.holdLast = bool(o, "hold_last", false);
        String interp = str(o, "interpolation", "catmull").toLowerCase(Locale.ROOT);
        b.interp = switch (interp) {
            case "linear" -> FrameAnimation.Interp.LINEAR;
            case "step", "none" -> FrameAnimation.Interp.STEP;
            default -> FrameAnimation.Interp.CATMULL;
        };
        b.fadeIn = (float) seconds(o, "fade_in", b.fps, 0.1);
        b.fadeOut = (float) seconds(o, "fade_out", b.fps, 0.15);
        b.speed = (float) num(o, "speed", 1.0);
        b.headMode = "additive".equalsIgnoreCase(str(o, "head", "absolute"))
                ? FrameAnimation.HeadMode.ADDITIVE : FrameAnimation.HeadMode.ABSOLUTE;
        b.realTime = "real".equalsIgnoreCase(str(o, "clock", "game"));
        if (o.has("first_person") && o.get("first_person").isJsonObject()) {
            JsonObject fp = o.getAsJsonObject("first_person");
            String mode = str(fp, "mode", "model").toLowerCase(Locale.ROOT);
            b.firstPerson = switch (mode) {
                case "vanilla" -> FrameAnimation.FirstPerson.VANILLA;
                case "none", "off", "hidden" -> FrameAnimation.FirstPerson.NONE;
                default -> FrameAnimation.FirstPerson.MODEL;
            };
            b.fpRight = bool(fp, "right_arm", true);
            b.fpLeft = bool(fp, "left_arm", true);
            b.fpUntil = (int) num(fp, "until", -1);
        }
        if (o.has("events") && o.get("events").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("events")) {
                if (!e.isJsonObject()) continue;
                JsonObject eo = e.getAsJsonObject();
                b.events.add(new FrameAnimation.Event((int) num(eo, "frame", 0), str(eo, "name", "event"), str(eo, "data", "")));
            }
        }
        if (o.has("secondary") && o.get("secondary").isJsonObject()) b.secondary = parseSecondary(o.getAsJsonObject("secondary"));
    }

    static FrameAnimation.Secondary parseSecondary(JsonObject s) {
        FrameAnimation.Secondary sec = new FrameAnimation.Secondary();
        sec.enabled = bool(s, "enabled", true);
        sec.amount = (float) num(s, "amount", sec.amount);
        sec.stiffness = (float) num(s, "stiffness", sec.stiffness);
        sec.damping = (float) num(s, "damping", sec.damping);
        sec.arms = (float) num(s, "arms", sec.arms);
        sec.legs = (float) num(s, "legs", sec.legs);
        sec.torso = (float) num(s, "torso", sec.torso);
        sec.head = (float) num(s, "head", sec.head);
        sec.body = (float) num(s, "body", sec.body);
        sec.breathing = (float) num(s, "breathing", sec.breathing);
        sec.breathPeriod = (float) num(s, "breath_period", sec.breathPeriod);
        sec.micro = (float) num(s, "micro", sec.micro);
        sec.inertia = (float) num(s, "inertia", sec.inertia);
        return sec;
    }

    // ------------------------------------------------------------------ 1. плотный покадровый

    private static FrameAnimation parseFrames(String defaultName, String source, JsonObject o) {
        Builder b = new Builder();
        common(b, o, defaultName, source);
        JsonArray frames = o.getAsJsonArray("frames");
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("пустой frames");
        b.alloc(frames.size());
        FrameChannel[] order = null;
        if (o.has("channels")) {
            JsonArray ch = o.getAsJsonArray("channels");
            order = new FrameChannel[ch.size()];
            for (int i = 0; i < ch.size(); i++) {
                order[i] = FrameChannel.byKey(ch.get(i).getAsString());
                if (order[i] == null) throw new IllegalArgumentException("неизвестный канал: " + ch.get(i).getAsString());
            }
        }
        float[] carry = new float[FrameChannel.COUNT];
        Arrays.fill(carry, Float.NaN);
        for (int f = 0; f < frames.size(); f++) {
            JsonElement fe = frames.get(f);
            if (fe.isJsonArray()) {
                if (order == null) throw new IllegalArgumentException("кадры-массивы требуют поле channels");
                JsonArray arr = fe.getAsJsonArray();
                for (int i = 0; i < order.length && i < arr.size(); i++) {
                    JsonElement v = arr.get(i);
                    if (v.isJsonNull()) continue;
                    b.set(f, order[i], order[i].toInternal(v.getAsFloat()));
                }
            } else if (fe.isJsonObject()) {
                for (Map.Entry<String, JsonElement> en : fe.getAsJsonObject().entrySet()) {
                    FrameChannel c = FrameChannel.byKey(en.getKey());
                    if (c == null) throw new IllegalArgumentException("неизвестный канал: " + en.getKey());
                    if (en.getValue().isJsonNull()) {
                        carry[c.ordinal()] = Float.NaN;
                    } else {
                        carry[c.ordinal()] = c.toInternal(en.getValue().getAsFloat());
                    }
                }
                for (int c = 0; c < FrameChannel.COUNT; c++) {
                    if (!Float.isNaN(carry[c])) b.values[f][c] = carry[c];
                }
            }
        }
        return b.build();
    }

    // ------------------------------------------------------------------ 2. ключи с кривыми

    private record Key(double frame, float value, FrameEasing.Fn ease) {
    }

    private static FrameAnimation parseKeys(String defaultName, String source, JsonObject o) {
        Builder b = new Builder();
        common(b, o, defaultName, source);
        Map<FrameChannel, List<Key>> tracks = new HashMap<>();
        double maxFrame = 0;
        if (o.has("tracks") && o.get("tracks").isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("tracks").entrySet()) {
                FrameChannel c = FrameChannel.byKey(en.getKey());
                if (c == null) throw new IllegalArgumentException("неизвестный канал: " + en.getKey());
                for (JsonElement ke : en.getValue().getAsJsonArray()) {
                    Key k;
                    if (ke.isJsonArray()) {
                        JsonArray a = ke.getAsJsonArray();
                        k = new Key(a.get(0).getAsDouble(), c.toInternal(a.get(1).getAsFloat()), a.size() > 2 ? ease(a.get(2)) : FrameEasing.LINEAR);
                    } else {
                        JsonObject ko = ke.getAsJsonObject();
                        k = new Key(num(ko, "frame", 0), c.toInternal((float) num(ko, "value", 0)),
                                ko.has("ease") ? ease(ko.get("ease")) : FrameEasing.LINEAR);
                    }
                    tracks.computeIfAbsent(c, x -> new ArrayList<>()).add(k);
                    maxFrame = Math.max(maxFrame, k.frame);
                }
            }
        }
        if (o.has("keys") && o.get("keys").isJsonArray()) {
            for (JsonElement ke : o.getAsJsonArray("keys")) {
                JsonObject ko = ke.getAsJsonObject();
                double frame = num(ko, "frame", 0);
                FrameEasing.Fn e = ko.has("ease") ? ease(ko.get("ease")) : FrameEasing.LINEAR;
                JsonObject pose = ko.has("pose") ? ko.getAsJsonObject("pose") : new JsonObject();
                for (Map.Entry<String, JsonElement> en : pose.entrySet()) {
                    FrameChannel c = FrameChannel.byKey(en.getKey());
                    if (c == null) throw new IllegalArgumentException("неизвестный канал: " + en.getKey());
                    tracks.computeIfAbsent(c, x -> new ArrayList<>()).add(new Key(frame, c.toInternal(en.getValue().getAsFloat()), e));
                }
                maxFrame = Math.max(maxFrame, frame);
            }
        }
        int length = (int) Math.ceil(o.has("length") ? num(o, "length", maxFrame + 1)
                : o.has("duration") ? num(o, "duration", 1.0) * b.fps : maxFrame + 1);
        b.alloc(Math.max(1, length));
        for (Map.Entry<FrameChannel, List<Key>> en : tracks.entrySet()) {
            List<Key> keys = en.getValue();
            keys.sort((x, y) -> Double.compare(x.frame, y.frame));
            for (int f = 0; f < b.frames; f++) {
                b.set(f, en.getKey(), evalKeys(keys, f));
            }
        }
        // ключи уже запечены в каждый кадр — между кадрами достаточно сглаживания проигрывателя
        return b.build();
    }

    private static float evalKeys(List<Key> keys, double f) {
        if (keys.isEmpty()) return Float.NaN;
        if (f <= keys.get(0).frame) return keys.get(0).value;
        Key last = keys.get(keys.size() - 1);
        if (f >= last.frame) return last.value;
        for (int i = 1; i < keys.size(); i++) {
            Key k1 = keys.get(i);
            if (f <= k1.frame) {
                Key k0 = keys.get(i - 1);
                double span = k1.frame - k0.frame;
                double u = span <= 0 ? 1.0 : (f - k0.frame) / span;
                return (float) (k0.value + (k1.value - k0.value) * k1.ease.apply(u));
            }
        }
        return last.value;
    }

    private static FrameEasing.Fn ease(JsonElement e) {
        if (e == null || e.isJsonNull()) return FrameEasing.LINEAR;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 4) {
                return FrameEasing.bezier(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble());
            }
            return FrameEasing.LINEAR;
        }
        return FrameEasing.byName(e.getAsString());
    }

    // ------------------------------------------------------------------ 3. Blockbench / Bedrock

    private static List<FrameAnimation> parseBedrock(String defaultName, String source, JsonObject o) {
        List<FrameAnimation> out = new ArrayList<>();
        JsonObject anims = o.getAsJsonObject("animations");
        float fps = (float) num(o, "jn_fps", 60.0);
        float[] rotSign = signs(o, "jn_rotation_sign", new float[]{-1f, -1f, 1f});
        float[] posSign = signs(o, "jn_position_sign", new float[]{-1f, 1f, 1f});
        for (Map.Entry<String, JsonElement> en : anims.entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject a = en.getValue().getAsJsonObject();
            String name = en.getKey();
            if (name.startsWith("animation.")) {
                String rest = name.substring("animation.".length());
                int dot = rest.indexOf('.');
                name = dot >= 0 && dot < rest.length() - 1 ? rest.substring(dot + 1) : rest;
            }
            Builder b = new Builder();
            JsonObject meta = o.has("jn") && o.get("jn").isJsonObject() ? o.getAsJsonObject("jn") : new JsonObject();
            common(b, meta, name, source);
            b.name = name.replace('.', '_');
            b.fps = fps;
            double length = num(a, "animation_length", 0.0);
            JsonElement loopEl = a.get("loop");
            if (loopEl != null && loopEl.isJsonPrimitive()) {
                JsonPrimitive lp = loopEl.getAsJsonPrimitive();
                if (lp.isBoolean()) b.loop = lp.getAsBoolean();
                else if ("hold_on_last_frame".equals(lp.getAsString())) b.holdLast = true;
            }
            JsonObject bones = a.has("bones") ? a.getAsJsonObject("bones") : new JsonObject();
            // длина по последнему ключу, если не задана
            if (length <= 0.0) {
                for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                    if (!bone.getValue().isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> ch : bone.getValue().getAsJsonObject().entrySet()) {
                        if (ch.getValue().isJsonObject()) {
                            for (String t : ch.getValue().getAsJsonObject().keySet()) {
                                length = Math.max(length, parseTime(t));
                            }
                        }
                    }
                }
            }
            int frames = Math.max(1, (int) Math.round(length * fps) + 1);
            b.alloc(frames);
            for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
                String part = bedrockPart(bone.getKey());
                if (part == null || !bone.getValue().isJsonObject()) continue;
                JsonObject bo = bone.getValue().getAsJsonObject();
                if (bo.has("rotation")) {
                    BedrockTrack tr = BedrockTrack.of(bo.get("rotation"));
                    for (int f = 0; f < frames; f++) {
                        double[] v = tr.eval(f / (double) fps);
                        b.set(f, FrameChannel.byKey(part + ".pitch"), FrameChannel.HEAD_PITCH.toInternal((float) v[0] * rotSign[0]));
                        b.set(f, FrameChannel.byKey(part + ".yaw"), FrameChannel.HEAD_PITCH.toInternal((float) v[1] * rotSign[1]));
                        b.set(f, FrameChannel.byKey(part + ".roll"), FrameChannel.HEAD_PITCH.toInternal((float) v[2] * rotSign[2]));
                    }
                }
                if (bo.has("position")) {
                    BedrockTrack tr = BedrockTrack.of(bo.get("position"));
                    // Bedrock: пиксели. Для всего тела — блоки.
                    float scale = "body".equals(part) ? 1f / 16f : 1f;
                    for (int f = 0; f < frames; f++) {
                        double[] v = tr.eval(f / (double) fps);
                        b.set(f, FrameChannel.byKey(part + ".x"), (float) v[0] * posSign[0] * scale);
                        b.set(f, FrameChannel.byKey(part + ".y"), (float) v[1] * posSign[1] * scale);
                        b.set(f, FrameChannel.byKey(part + ".z"), (float) v[2] * posSign[2] * scale);
                    }
                }
            }
            out.add(b.build());
        }
        return out;
    }

    private static String bedrockPart(String bone) {
        String b = bone.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return switch (b) {
            case "head" -> "head";
            case "body", "torso", "chest" -> "torso";
            case "rightarm", "armright" -> "rightArm";
            case "leftarm", "armleft" -> "leftArm";
            case "rightleg", "legright" -> "rightLeg";
            case "leftleg", "legleft" -> "leftLeg";
            case "root", "waist", "main", "player", "all", "bodyroot" -> "body";
            default -> null;
        };
    }

    /** Дорожка Bedrock: константа, число или словарь времени → значение / {pre, post, lerp_mode}. */
    private static final class BedrockTrack {
        final TreeMap<Double, double[][]> keys = new TreeMap<>(); // [pre, post]
        final Map<Double, String> modes = new HashMap<>();
        double[] constant;

        static BedrockTrack of(JsonElement e) {
            BedrockTrack t = new BedrockTrack();
            if (e.isJsonObject()) {
                for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                    double time = parseTime(en.getKey());
                    JsonElement v = en.getValue();
                    if (v.isJsonObject()) {
                        JsonObject vo = v.getAsJsonObject();
                        double[] post = vec(vo.has("post") ? vo.get("post") : vo.get("pre"));
                        double[] pre = vec(vo.has("pre") ? vo.get("pre") : vo.get("post"));
                        t.keys.put(time, new double[][]{pre, post});
                        if (vo.has("lerp_mode")) t.modes.put(time, vo.get("lerp_mode").getAsString());
                    } else {
                        double[] val = vec(v);
                        t.keys.put(time, new double[][]{val, val});
                    }
                }
            } else {
                t.constant = vec(e);
            }
            return t;
        }

        double[] eval(double time) {
            if (constant != null || keys.isEmpty()) return constant != null ? constant : new double[3];
            Map.Entry<Double, double[][]> lo = keys.floorEntry(time);
            Map.Entry<Double, double[][]> hi = keys.ceilingEntry(time);
            if (lo == null) return hi.getValue()[0];
            if (hi == null) return lo.getValue()[1];
            if (lo.getKey().equals(hi.getKey())) return lo.getValue()[1];
            double u = (time - lo.getKey()) / (hi.getKey() - lo.getKey());
            String mode = modes.getOrDefault(lo.getKey(), "linear");
            double[] a = lo.getValue()[1], bb = hi.getValue()[0];
            double[] r = new double[3];
            if ("step".equals(mode)) return a;
            if ("catmullrom".equals(mode) || "catmullrom".equals(modes.get(hi.getKey()))) {
                Map.Entry<Double, double[][]> before = keys.lowerEntry(lo.getKey());
                Map.Entry<Double, double[][]> after = keys.higherEntry(hi.getKey());
                double[] p0 = before != null ? before.getValue()[1] : a;
                double[] p3 = after != null ? after.getValue()[0] : bb;
                for (int i = 0; i < 3; i++) {
                    r[i] = FrameAnimation.catmull((float) p0[i], (float) a[i], (float) bb[i], (float) p3[i], (float) u);
                }
                return r;
            }
            for (int i = 0; i < 3; i++) r[i] = a[i] + (bb[i] - a[i]) * u;
            return r;
        }
    }

    private static double parseTime(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double[] vec(JsonElement e) {
        double[] r = new double[3];
        if (e == null || e.isJsonNull()) return r;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < 3 && i < a.size(); i++) r[i] = molang(a.get(i));
        } else {
            double v = molang(e);
            r[0] = r[1] = r[2] = v;
        }
        return r;
    }

    /** Molang-выражения не вычисляем — только числа (строка-число тоже подходит). */
    private static double molang(JsonElement e) {
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
            return Double.parseDouble(e.getAsString().trim());
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    private static float[] signs(JsonObject o, String key, float[] def) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return def;
        JsonArray a = o.getAsJsonArray(key);
        float[] r = def.clone();
        for (int i = 0; i < 3 && i < a.size(); i++) r[i] = a.get(i).getAsFloat();
        return r;
    }

    // ------------------------------------------------------------------ 4. эмоции Player Animator

    private record EKey(int tick, float value, FrameEasing.Fn ease) {
    }

    private static FrameAnimation parseEmote(String defaultName, String source, JsonObject o) {
        JsonObject emote = o.getAsJsonObject("emote");
        Builder b = new Builder();
        b.name = str(o, "name", defaultName);
        b.source = source;
        b.fps = 20f; // эмоции Player Animator — в тиках
        int begin = (int) num(emote, "beginTick", 0);
        int end = (int) num(emote, "endTick", 1);
        int stop = (int) num(emote, "stopTick", end);
        boolean degrees = bool(emote, "degrees", true);
        boolean easeBefore = bool(emote, "easeBeforeKeyframe", false);
        b.loop = bool(emote, "isLoop", false);
        b.loopFrom = (int) num(emote, "returnTick", 0);
        b.fadeIn = 0f;
        b.fadeOut = Math.max(0, stop - end) / 20f;
        Map<FrameChannel, List<EKey>> tracks = new HashMap<>();
        if (emote.has("moves")) {
            for (JsonElement me : emote.getAsJsonArray("moves")) {
                JsonObject m = me.getAsJsonObject();
                int tick = (int) num(m, "tick", 0);
                FrameEasing.Fn e = FrameEasing.byName(str(m, "easing", "linear"));
                for (Map.Entry<String, JsonElement> part : m.entrySet()) {
                    if (!part.getValue().isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> ch : part.getValue().getAsJsonObject().entrySet()) {
                        FrameChannel c = FrameChannel.byKey(part.getKey() + "." + ch.getKey());
                        if (c == null) continue;
                        float v = ch.getValue().getAsFloat();
                        float internal = c.isAngle() && !degrees ? v : c.toInternal(v);
                        tracks.computeIfAbsent(c, x -> new ArrayList<>()).add(new EKey(tick, internal, e));
                    }
                }
            }
        }
        b.alloc(Math.max(1, end - begin + 1));
        for (Map.Entry<FrameChannel, List<EKey>> en : tracks.entrySet()) {
            List<EKey> keys = en.getValue();
            keys.sort((x, y) -> Integer.compare(x.tick, y.tick));
            for (int f = 0; f < b.frames; f++) {
                int t = begin + f;
                b.set(f, en.getKey(), evalEmote(keys, t, easeBefore));
            }
        }
        return b.build();
    }

    private static float evalEmote(List<EKey> keys, int t, boolean easeBefore) {
        EKey first = keys.get(0);
        if (t <= first.tick) return first.value;
        for (int i = 1; i < keys.size(); i++) {
            EKey k1 = keys.get(i);
            if (t <= k1.tick) {
                EKey k0 = keys.get(i - 1);
                double u = k1.tick == k0.tick ? 1.0 : (t - k0.tick) / (double) (k1.tick - k0.tick);
                FrameEasing.Fn e = easeBefore ? k1.ease : k0.ease;
                return (float) (k0.value + (k1.value - k0.value) * e.apply(u));
            }
        }
        return keys.get(keys.size() - 1).value;
    }

    // ------------------------------------------------------------------ JSON-помощники

    static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    static double num(JsonObject o, String k, double def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    /** Время в секундах; строка вида "6f" — в кадрах. */
    private static double seconds(JsonObject o, String k, float fps, double def) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) return def;
        String s = o.get(k).getAsString().trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("f")) return Double.parseDouble(s.substring(0, s.length() - 1)) / fps;
            if (s.endsWith("ms")) return Double.parseDouble(s.substring(0, s.length() - 2)) / 1000.0;
            if (s.endsWith("s")) return Double.parseDouble(s.substring(0, s.length() - 1));
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
