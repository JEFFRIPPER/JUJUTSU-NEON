package com.kira.jujutsuneon.rbx;

import java.util.Locale;

/**
 * Приоритет анимации — как Enum.AnimationPriority в Roblox. Трек с более высоким приоритетом перекрывает
 * нижние на тех костях, которые он анимирует (на остальных видно нижние). Треки одного приоритета
 * смешиваются по весам.
 */
public enum AnimationPriority {
    /** Самый низкий: базовое движение (стойка, ходьба, бег…) из Animate. */
    CORE,
    /** Поза поверх движения, например «держит предмет». */
    IDLE,
    /** Особые движения: дэши, полёт. */
    MOVEMENT,
    /** Действия и техники. По умолчанию для анимаций, запускаемых из кода. */
    ACTION,
    ACTION2,
    ACTION3,
    ACTION4;

    public static AnimationPriority parse(String s, AnimationPriority def) {
        if (s == null) return def;
        String k = s.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        for (AnimationPriority p : values()) {
            if (p.name().equals(k)) return p;
        }
        return def;
    }
}
