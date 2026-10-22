package com.kira.jujutsuneon.anim;

import java.util.Locale;

/**
 * Кости скелета игрока. Иерархия:
 *
 * <pre>
 * ROOT (всё тело, поворот вокруг таза)
 *  ├─ WAIST (корпус, поворот вокруг таза)
 *  │   ├─ HEAD
 *  │   ├─ RIGHT_ARM
 *  │   └─ LEFT_ARM
 *  ├─ RIGHT_LEG
 *  └─ LEFT_LEG
 * </pre>
 *
 * Руки и голова — дети корпуса: когда корпус наклоняется или поворачивается, плечи и шея идут
 * за ним, а не остаются висеть на месте, как у ванильной модели.
 */
public enum Joint {
    ROOT("body", null),
    WAIST("torso", ROOT),
    HEAD("head", WAIST),
    RIGHT_ARM("rightArm", WAIST),
    LEFT_ARM("leftArm", WAIST),
    RIGHT_LEG("rightLeg", ROOT),
    LEFT_LEG("leftLeg", ROOT);

    public static final Joint[] ALL = values();
    public static final int COUNT = ALL.length;

    /** Имя части у Player Animator и в файлах анимаций. */
    public final String partName;
    public final Joint parent;

    Joint(String partName, Joint parent) {
        this.partName = partName;
        this.parent = parent;
    }

    public boolean isLimb() {
        return this == RIGHT_ARM || this == LEFT_ARM || this == RIGHT_LEG || this == LEFT_LEG;
    }

    public Joint mirror() {
        return switch (this) {
            case RIGHT_ARM -> LEFT_ARM;
            case LEFT_ARM -> RIGHT_ARM;
            case RIGHT_LEG -> LEFT_LEG;
            case LEFT_LEG -> RIGHT_LEG;
            default -> this;
        };
    }

    /** Имя из файла: "rightArm", "right_arm", "torso", "body", "root", "waist"… */
    public static Joint byName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (n) {
            case "body", "root", "all", "whole" -> ROOT;
            case "torso", "waist", "chest", "spine" -> WAIST;
            case "head" -> HEAD;
            case "rightarm", "armr", "rarm" -> RIGHT_ARM;
            case "leftarm", "arml", "larm" -> LEFT_ARM;
            case "rightleg", "legr", "rleg" -> RIGHT_LEG;
            case "leftleg", "legl", "lleg" -> LEFT_LEG;
            default -> null;
        };
    }

    public static Joint byPartName(String part) {
        for (Joint j : ALL) if (j.partName.equals(part)) return j;
        return null;
    }
}
