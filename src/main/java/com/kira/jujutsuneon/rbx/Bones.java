package com.kira.jujutsuneon.rbx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Сквозная нумерация костей по имени (кости R15 — первыми, любые другие добавляются по мере появления). */
public final class Bones {

    public static final String LOWER_TORSO = "LowerTorso", UPPER_TORSO = "UpperTorso", HEAD = "Head";
    public static final String R_UPPER_ARM = "RightUpperArm", R_LOWER_ARM = "RightLowerArm", R_HAND = "RightHand";
    public static final String L_UPPER_ARM = "LeftUpperArm", L_LOWER_ARM = "LeftLowerArm", L_HAND = "LeftHand";
    public static final String R_UPPER_LEG = "RightUpperLeg", R_LOWER_LEG = "RightLowerLeg", R_FOOT = "RightFoot";
    public static final String L_UPPER_LEG = "LeftUpperLeg", L_LOWER_LEG = "LeftLowerLeg", L_FOOT = "LeftFoot";

    /** Кости R15 в порядке иерархии. */
    public static final String[] R15 = {LOWER_TORSO, UPPER_TORSO, HEAD,
            R_UPPER_ARM, R_LOWER_ARM, R_HAND, L_UPPER_ARM, L_LOWER_ARM, L_HAND,
            R_UPPER_LEG, R_LOWER_LEG, R_FOOT, L_UPPER_LEG, L_LOWER_LEG, L_FOOT};

    private static final Map<String, Integer> INDEX = new HashMap<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static int[] mirror = new int[0];

    static {
        for (String n : R15) index(n);
    }

    private Bones() {
    }

    public static synchronized int index(String name) {
        Integer i = INDEX.get(name);
        if (i != null) return i;
        int id = NAMES.size();
        NAMES.add(name);
        INDEX.put(name, id);
        mirror = null;
        return id;
    }

    /** Номер кости или −1, если такой ещё не было. */
    public static synchronized int find(String name) {
        Integer i = INDEX.get(name);
        return i == null ? -1 : i;
    }

    public static synchronized String name(int i) {
        return NAMES.get(i);
    }

    public static synchronized int count() {
        return NAMES.size();
    }

    /** Зеркальная кость (Right ↔ Left) — для зеркального проигрывания. */
    public static synchronized int mirrored(int i) {
        if (mirror == null || mirror.length != NAMES.size()) {
            int[] m = new int[NAMES.size()];
            for (int k = 0; k < m.length; k++) {
                String n = NAMES.get(k);
                String o = n.startsWith("Right") ? "Left" + n.substring(5) : n.startsWith("Left") ? "Right" + n.substring(4) : n;
                Integer j = INDEX.get(o);
                m[k] = j == null ? k : j;
            }
            mirror = m;
        }
        return mirror[i];
    }
}
