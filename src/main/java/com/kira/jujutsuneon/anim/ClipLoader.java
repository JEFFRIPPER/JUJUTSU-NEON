package com.kira.jujutsuneon.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чтение клипов из JSON. Два формата:
 *
 * <b>jn_anim</b> (основной, см. docs/ANIMATION.md):
 * <pre>
 * {"format": "jn_anim", "time": "ticks", "length": 24, "fade_in": 0.12, "fade_out": 0.2,
 *  "keys": [
 *    {"t": 0,  "pose": {"rightArm": [0, 0, 4, -6], "torso": [0, 0, 0]}},
 *    {"t": 6,  "ease": "out", "pose": {"rightArm": [-90, 0, 10, -20], "body.y": -0.05}}
 *  ],
 *  "tracks": {"head.yaw": [[0, 0], [12, 20, "smooth"]]}}
 * </pre>
 *
 * <b>Player Animator</b> (старые анимации мода, assets/jujutsu_neon/player_animation/*.json) —
 * читаются как есть и переводятся на новый движок: сгиб корпуса превращается в наклон корпуса
 * от таза (руки и голова идут за ним), ключи соединяются плавными кривыми без остановок.
 */
public final class ClipLoader {

    /** Сколько градусов наклона корпуса даёт градус старого сгиба корпуса (bendy-lib). */
    static final double LEGACY_TORSO_BEND_TO_PITCH = 0.6;

    private ClipLoader() {
    }

    public static Clip load(String name, Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) throw new IllegalArgumentException("ожидался JSON-объект");
        JsonObject o = root.getAsJsonObject();
        if (o.has("emote")) return loadLegacy(name, o);
        return loadJn(name, o);
    }

    // ================================================================== jn_anim

    private static Clip loadJn(String name, JsonObject o) {
        String timeUnit = str(o, "time", "ticks").toLowerCase(Locale.ROOT);
        double fps = num(o, "fps", 20);
        double unit = switch (timeUnit) {
            case "seconds", "s", "sec" -> 1.0;
            case "frames", "f" -> 1.0 / fps;
            default -> 1.0 / 20.0; // тики
        };
        Clip.Builder b = new Clip.Builder(name);
        b.length(num(o, "length", 20) * unit);
        b.loop(bool(o, "loop", false));
        b.speed(num(o, "speed", 1));
        b.fade(seconds(o, "fade_in", 0.15, unit), seconds(o, "fade_out", 0.2, unit));
        b.holdLast(bool(o, "hold_last", false));
        b.additive(bool(o, "additive", false));
        b.look(num(o, "look", 0));
        b.worldLimbs(bool(o, "world_limbs", false));
        if (o.has("first_person") && o.get("first_person").isJsonObject()) {
            JsonObject fp = o.getAsJsonObject("first_person");
            String mode = str(fp, "mode", "model").toLowerCase(Locale.ROOT);
            Clip.FirstPerson m = switch (mode) {
                case "vanilla" -> Clip.FirstPerson.VANILLA;
                case "none", "off" -> Clip.FirstPerson.NONE;
                default -> Clip.FirstPerson.MODEL;
            };
            double until = fp.has("until") ? num(fp, "until", -1) * unit : -1;
            b.firstPerson(m, bool(fp, "right_arm", true), bool(fp, "left_arm", true), until);
        }
        if (o.has("mask") && o.get("mask").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("mask").entrySet()) {
                Joint j = Joint.byName(e.getKey());
                if (j == null) throw new IllegalArgumentException("mask: неизвестная кость " + e.getKey());
                b.mask(j, e.getValue().getAsDouble());
            }
        }
        Curve.Mode defMode = Curve.Mode.parse(str(o, "ease", "smooth"));

        if (o.has("keys")) {
            for (JsonElement ke : o.getAsJsonArray("keys")) {
                JsonObject k = ke.getAsJsonObject();
                double t = num(k, "t", num(k, "tick", num(k, "frame", 0))) * unit;
                Curve.Mode mode = k.has("ease") ? Curve.Mode.parse(k.get("ease").getAsString()) : defMode;
                if (!k.has("pose")) continue;
                for (Map.Entry<String, JsonElement> e : k.getAsJsonObject("pose").entrySet()) {
                    putPoseEntry(b, e.getKey(), e.getValue(), t, mode);
                }
            }
        }
        if (o.has("tracks")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tracks").entrySet()) {
                int dot = e.getKey().lastIndexOf('.');
                if (dot < 0) throw new IllegalArgumentException("tracks: ожидалось «кость.канал»: " + e.getKey());
                Joint j = joint(e.getKey().substring(0, dot));
                int c = channel(e.getKey().substring(dot + 1));
                for (JsonElement pe : e.getValue().getAsJsonArray()) {
                    JsonArray p = pe.getAsJsonArray();
                    double t = p.get(0).getAsDouble() * unit;
                    double v = p.get(1).getAsDouble();
                    Curve.Mode mode = p.size() > 2 ? Curve.Mode.parse(p.get(2).getAsString()) : defMode;
                    b.key(j, c, t, v, mode);
                }
            }
        }
        return b.build();
    }

    private static void putPoseEntry(Clip.Builder b, String key, JsonElement v, double t, Curve.Mode mode) {
        int dot = key.indexOf('.');
        if (dot >= 0) {
            Joint j = joint(key.substring(0, dot));
            int c = channel(key.substring(dot + 1));
            b.key(j, c, t, v.getAsDouble(), mode);
            return;
        }
        Joint j = joint(key);
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            // [pitch, yaw, roll] или [pitch, yaw, roll, bend]
            for (int i = 0; i < a.size() && i < 4; i++) {
                int c = i < 3 ? i : Clip.BEND;
                b.key(j, c, t, a.get(i).getAsDouble(), mode);
            }
        } else if (v.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                b.key(j, channel(e.getKey()), t, e.getValue().getAsDouble(), mode);
            }
        } else {
            throw new IllegalArgumentException("поза: у «" + key + "» ожидался массив или объект");
        }
    }

    // ================================================================== Player Animator (старый формат)

    private static Clip loadLegacy(String name, JsonObject o) {
        JsonObject e = o.getAsJsonObject("emote");
        boolean degrees = bool(e, "degrees", false);
        double end = num(e, "endTick", 20);
        double stop = num(e, "stopTick", end + 4);
        Clip.Builder b = new Clip.Builder(name);
        b.length(end / 20.0);
        b.loop(bool(e, "isLoop", false));
        b.fade(0.15, Math.max(0.05, (stop - end) / 20.0));
        b.worldLimbs(true);

        // Корпус: наклон + сгиб (и сгиб «body») собираем отдельно, потом складываем в один наклон.
        Curve.Builder torsoPitch = new Curve.Builder(), torsoBend = new Curve.Builder();
        List<double[]> waistTimes = new ArrayList<>();
        List<Curve.Mode> waistModes = new ArrayList<>();

        // ключи по каналам: сначала собрать, потом «развернуть» углы (360 → 0 не крутит назад)
        @SuppressWarnings("unchecked")
        List<double[]>[][] raw = new List[Joint.COUNT][Clip.CHANNELS];
        @SuppressWarnings("unchecked")
        List<Curve.Mode>[][] rawModes = new List[Joint.COUNT][Clip.CHANNELS];

        for (JsonElement me : e.getAsJsonArray("moves")) {
            JsonObject m = me.getAsJsonObject();
            double t = num(m, "tick", 0) / 20.0;
            Curve.Mode mode = Curve.Mode.parse(str(m, "easing", "linear"));
            for (Map.Entry<String, JsonElement> pe : m.entrySet()) {
                if (!pe.getValue().isJsonObject()) continue;
                String part = pe.getKey();
                Joint j = Joint.byName(part);
                if (j == null) continue;
                for (Map.Entry<String, JsonElement> ce : pe.getValue().getAsJsonObject().entrySet()) {
                    int c = Clip.channelByName(ce.getKey());
                    if (c < 0) continue;
                    double v = ce.getValue().getAsDouble();
                    boolean angle = c == Clip.PITCH || c == Clip.YAW || c == Clip.ROLL || c == Clip.BEND || c == Clip.AXIS;
                    if (angle && !degrees) v = Math.toDegrees(v);
                    if (j == Joint.WAIST && (c == Clip.PITCH || c == Clip.BEND)) {
                        (c == Clip.PITCH ? torsoPitch : torsoBend).key(t, v, mode);
                        addTime(waistTimes, waistModes, t, mode);
                        continue;
                    }
                    if (j == Joint.ROOT && c == Clip.BEND) {
                        torsoBend.key(t, v, mode); // сгиб «body» у Player Animator прибавляется к сгибу корпуса
                        addTime(waistTimes, waistModes, t, mode);
                        continue;
                    }
                    List<double[]> list = raw[j.ordinal()][c];
                    if (list == null) {
                        raw[j.ordinal()][c] = list = new ArrayList<>();
                        rawModes[j.ordinal()][c] = new ArrayList<>();
                    }
                    list.add(new double[]{t, v});
                    rawModes[j.ordinal()][c].add(mode);
                }
            }
        }

        for (int j = 0; j < Joint.COUNT; j++) {
            for (int c = 0; c < Clip.CHANNELS; c++) {
                List<double[]> list = raw[j][c];
                if (list == null) continue;
                boolean angle = c == Clip.PITCH || c == Clip.YAW || c == Clip.ROLL;
                List<Integer> order = sortedOrder(list);
                double prev = Double.NaN;
                for (int idx : order) {
                    double[] kv = list.get(idx);
                    double v = kv[1];
                    if (angle && !Double.isNaN(prev)) {
                        while (v - prev > 180) v -= 360;
                        while (v - prev < -180) v += 360;
                    }
                    prev = v;
                    b.key(Joint.ALL[j], c, kv[0], v, rawModes[j][c].get(idx));
                }
            }
        }

        if (!waistTimes.isEmpty()) {
            Curve pc = torsoPitch.build(false, 0), bc = torsoBend.build(false, 0);
            for (int i = 0; i < waistTimes.size(); i++) {
                double t = waistTimes.get(i)[0];
                double v = (pc != null ? pc.sample(t) : 0) + LEGACY_TORSO_BEND_TO_PITCH * (bc != null ? bc.sample(t) : 0);
                b.key(Joint.WAIST, Clip.PITCH, t, v, waistModes.get(i));
            }
        }
        return b.build();
    }

    private static void addTime(List<double[]> times, List<Curve.Mode> modes, double t, Curve.Mode mode) {
        for (double[] x : times) if (Math.abs(x[0] - t) < 1.0E-7) return;
        times.add(new double[]{t});
        modes.add(mode);
    }

    private static List<Integer> sortedOrder(List<double[]> list) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) idx.add(i);
        idx.sort((a, c) -> Double.compare(list.get(a)[0], list.get(c)[0]));
        return idx;
    }

    // ================================================================== мелочи

    private static Joint joint(String s) {
        Joint j = Joint.byName(s);
        if (j == null) throw new IllegalArgumentException("неизвестная кость «" + s + "»");
        return j;
    }

    private static int channel(String s) {
        int c = Clip.channelByName(s);
        if (c < 0) throw new IllegalArgumentException("неизвестный канал «" + s + "»");
        return c;
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    /** Секунды: число — секунды, строка "6t" — тики, "120ms" — миллисекунды, "3f" — единицы файла. */
    private static double seconds(JsonObject o, String k, double def, double unit) {
        if (!o.has(k)) return def;
        JsonElement e = o.get(k);
        if (e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("ms")) return Double.parseDouble(s.substring(0, s.length() - 2)) / 1000.0;
        if (s.endsWith("t")) return Double.parseDouble(s.substring(0, s.length() - 1)) / 20.0;
        if (s.endsWith("f")) return Double.parseDouble(s.substring(0, s.length() - 1)) * unit;
        if (s.endsWith("s")) return Double.parseDouble(s.substring(0, s.length() - 1));
        return Double.parseDouble(s);
    }
}
