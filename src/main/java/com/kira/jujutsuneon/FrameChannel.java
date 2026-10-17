package com.kira.jujutsuneon;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Каналы позы покадровой анимации. Каждый кадр анимации может задать любой из них.
 *
 * Единицы в файлах:
 *  - повороты (pitch / yaw / roll), сгиб (bend) и направление сгиба (bend_axis) — градусы;
 *  - смещение всего тела (body.x / y / z) — блоки;
 *  - смещение частей (head / torso / руки / ноги .x / y / z) — пиксели модели (1/16 блока), прибавляется к ванильному.
 *
 * Знаки (как в Player Animator и во всех анимациях мода):
 *  - рука/нога pitch &lt; 0 — вперёд-вверх (-90 — рука вытянута вперёд);
 *  - правая рука/нога roll &gt; 0 — наружу, левая roll &lt; 0 — наружу;
 *  - правая рука yaw &lt; 0 — внутрь (к центру), левая yaw &gt; 0 — внутрь;
 *  - сгиб руки &lt; 0 — локоть сгибается (предплечье вперёд), сгиб ноги &gt; 0 — колено (голень назад);
 *  - head pitch &gt; 0 — взгляд вниз; torso bend &gt; 0 — корпус наклоняется вперёд;
 *  - body pitch &gt; 0 — отклониться назад, yaw &gt; 0 — поворот влево, roll &gt; 0 — завалиться влево;
 *    body поворачивается вокруг точки на высоте 0,7 блока (таз).
 */
public enum FrameChannel {
    BODY_X("body", Kind.POS_X, Unit.BLOCKS),
    BODY_Y("body", Kind.POS_Y, Unit.BLOCKS),
    BODY_Z("body", Kind.POS_Z, Unit.BLOCKS),
    BODY_PITCH("body", Kind.PITCH, Unit.DEGREES),
    BODY_YAW("body", Kind.YAW, Unit.DEGREES),
    BODY_ROLL("body", Kind.ROLL, Unit.DEGREES),

    HEAD_X("head", Kind.POS_X, Unit.PIXELS),
    HEAD_Y("head", Kind.POS_Y, Unit.PIXELS),
    HEAD_Z("head", Kind.POS_Z, Unit.PIXELS),
    HEAD_PITCH("head", Kind.PITCH, Unit.DEGREES),
    HEAD_YAW("head", Kind.YAW, Unit.DEGREES),
    HEAD_ROLL("head", Kind.ROLL, Unit.DEGREES),

    TORSO_X("torso", Kind.POS_X, Unit.PIXELS),
    TORSO_Y("torso", Kind.POS_Y, Unit.PIXELS),
    TORSO_Z("torso", Kind.POS_Z, Unit.PIXELS),
    TORSO_PITCH("torso", Kind.PITCH, Unit.DEGREES),
    TORSO_YAW("torso", Kind.YAW, Unit.DEGREES),
    TORSO_ROLL("torso", Kind.ROLL, Unit.DEGREES),
    TORSO_BEND("torso", Kind.BEND, Unit.DEGREES),
    TORSO_BEND_AXIS("torso", Kind.BEND_AXIS, Unit.DEGREES),

    RIGHT_ARM_X("rightArm", Kind.POS_X, Unit.PIXELS),
    RIGHT_ARM_Y("rightArm", Kind.POS_Y, Unit.PIXELS),
    RIGHT_ARM_Z("rightArm", Kind.POS_Z, Unit.PIXELS),
    RIGHT_ARM_PITCH("rightArm", Kind.PITCH, Unit.DEGREES),
    RIGHT_ARM_YAW("rightArm", Kind.YAW, Unit.DEGREES),
    RIGHT_ARM_ROLL("rightArm", Kind.ROLL, Unit.DEGREES),
    RIGHT_ARM_BEND("rightArm", Kind.BEND, Unit.DEGREES),
    RIGHT_ARM_BEND_AXIS("rightArm", Kind.BEND_AXIS, Unit.DEGREES),

    LEFT_ARM_X("leftArm", Kind.POS_X, Unit.PIXELS),
    LEFT_ARM_Y("leftArm", Kind.POS_Y, Unit.PIXELS),
    LEFT_ARM_Z("leftArm", Kind.POS_Z, Unit.PIXELS),
    LEFT_ARM_PITCH("leftArm", Kind.PITCH, Unit.DEGREES),
    LEFT_ARM_YAW("leftArm", Kind.YAW, Unit.DEGREES),
    LEFT_ARM_ROLL("leftArm", Kind.ROLL, Unit.DEGREES),
    LEFT_ARM_BEND("leftArm", Kind.BEND, Unit.DEGREES),
    LEFT_ARM_BEND_AXIS("leftArm", Kind.BEND_AXIS, Unit.DEGREES),

    RIGHT_LEG_X("rightLeg", Kind.POS_X, Unit.PIXELS),
    RIGHT_LEG_Y("rightLeg", Kind.POS_Y, Unit.PIXELS),
    RIGHT_LEG_Z("rightLeg", Kind.POS_Z, Unit.PIXELS),
    RIGHT_LEG_PITCH("rightLeg", Kind.PITCH, Unit.DEGREES),
    RIGHT_LEG_YAW("rightLeg", Kind.YAW, Unit.DEGREES),
    RIGHT_LEG_ROLL("rightLeg", Kind.ROLL, Unit.DEGREES),
    RIGHT_LEG_BEND("rightLeg", Kind.BEND, Unit.DEGREES),
    RIGHT_LEG_BEND_AXIS("rightLeg", Kind.BEND_AXIS, Unit.DEGREES),

    LEFT_LEG_X("leftLeg", Kind.POS_X, Unit.PIXELS),
    LEFT_LEG_Y("leftLeg", Kind.POS_Y, Unit.PIXELS),
    LEFT_LEG_Z("leftLeg", Kind.POS_Z, Unit.PIXELS),
    LEFT_LEG_PITCH("leftLeg", Kind.PITCH, Unit.DEGREES),
    LEFT_LEG_YAW("leftLeg", Kind.YAW, Unit.DEGREES),
    LEFT_LEG_ROLL("leftLeg", Kind.ROLL, Unit.DEGREES),
    LEFT_LEG_BEND("leftLeg", Kind.BEND, Unit.DEGREES),
    LEFT_LEG_BEND_AXIS("leftLeg", Kind.BEND_AXIS, Unit.DEGREES);

    enum Kind { POS_X, POS_Y, POS_Z, PITCH, YAW, ROLL, BEND, BEND_AXIS }

    enum Unit { DEGREES, BLOCKS, PIXELS }

    /** Часть модели в терминах Player Animator: body, head, torso, rightArm, leftArm, rightLeg, leftLeg. */
    final String part;
    final Kind kind;
    final Unit unit;
    /** Имя в файлах: "rightArm.pitch", "body.y" и т.д. */
    final String key;

    static final FrameChannel[] ALL = values();
    static final int COUNT = ALL.length;
    static final String[] PARTS = {"body", "head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg"};

    private static final Map<String, FrameChannel> BY_KEY = new HashMap<>();

    static {
        for (FrameChannel c : ALL) {
            BY_KEY.put(c.key.toLowerCase(Locale.ROOT), c);
        }
        // Синонимы — чтобы файлы из других редакторов читались без правок.
        alias("right_arm", "rightArm");
        alias("left_arm", "leftArm");
        alias("right_leg", "rightLeg");
        alias("left_leg", "leftLeg");
        alias("rightarm", "rightArm");
        alias("leftarm", "leftArm");
        alias("rightleg", "rightLeg");
        alias("leftleg", "leftLeg");
        alias("root", "body");
        alias("chest", "torso");
    }

    private static void alias(String alias, String part) {
        for (FrameChannel c : ALL) {
            if (c.part.equals(part)) {
                String suffix = c.key.substring(c.key.indexOf('.'));
                BY_KEY.put((alias + suffix).toLowerCase(Locale.ROOT), c);
            }
        }
    }

    FrameChannel(String part, Kind kind, Unit unit) {
        this.part = part;
        this.kind = kind;
        this.unit = unit;
        String k = switch (kind) {
            case POS_X -> "x";
            case POS_Y -> "y";
            case POS_Z -> "z";
            case PITCH -> "pitch";
            case YAW -> "yaw";
            case ROLL -> "roll";
            case BEND -> "bend";
            case BEND_AXIS -> "bend_axis";
        };
        this.key = part + "." + k;
    }

    /** Канал по имени из файла (регистр не важен, есть синонимы: right_arm, root, chest, axis вместо bend_axis). */
    static FrameChannel byKey(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.endsWith(".axis")) k = k.substring(0, k.length() - 5) + ".bend_axis";
        if (k.endsWith(".bendaxis")) k = k.substring(0, k.length() - 9) + ".bend_axis";
        return BY_KEY.get(k);
    }

    /** Значение из файла → внутреннее (градусы → радианы, пиксели и блоки как есть). */
    float toInternal(float fileValue) {
        return unit == Unit.DEGREES ? fileValue * ((float) Math.PI / 180f) : fileValue;
    }

    float toFile(float internal) {
        return unit == Unit.DEGREES ? internal * (180f / (float) Math.PI) : internal;
    }

    boolean isAngle() {
        return unit == Unit.DEGREES;
    }

    /** Зеркальный канал (левая ↔ правая сторона) и знак, с которым переносится значение. */
    FrameChannel mirror() {
        String p = switch (part) {
            case "rightArm" -> "leftArm";
            case "leftArm" -> "rightArm";
            case "rightLeg" -> "leftLeg";
            case "leftLeg" -> "rightLeg";
            default -> part;
        };
        return byKey(p + key.substring(key.indexOf('.')));
    }

    /** При зеркалировании yaw, roll, направление сгиба и смещение по X меняют знак, остальное — нет. */
    float mirrorSign() {
        return switch (kind) {
            case YAW, ROLL, POS_X, BEND_AXIS -> -1f;
            default -> 1f;
        };
    }
}
