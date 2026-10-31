package com.kira.jujutsuneon.rbx;

/** Состояние персонажа для {@link AnimateScript} (заполняется каждый кадр). */
public final class AnimateInput {
    /** Прошло с прошлого кадра, секунды. */
    public double dt;
    /** Скорость по горизонтали, блоки/с; доля «спиной вперёд» (−1..1, +1 — строго вперёд). */
    public double speed, forward = 1;
    /** Скорость по вертикали, блоки/с (+вверх). */
    public double vy;
    /** Поворот тела, градусы/с (+вправо); разгон вперёд, блоки/с². */
    public double turnRate, accel;
    public boolean onGround, crouching, sprinting;
    public boolean swimming, inWater, climbing, riding, fallFlying, sleeping, spinAttack, dead;

    // ---- движения мода (MovementFxClient)
    /** Зарядка прыжка: вес 0..1 и время с начала, секунды. */
    public double chargeWeight, chargeAge;
    /** Взлёт после заряженного прыжка, секунды (−1 — нет). */
    public double leapAge = -1;
    /** Полёт: веса, движение 0..1, угол скорости над горизонтом (градусы), крен −1..1. */
    public double flightW, boostW, landW, flightMove, elevation, bank;
    /** Дэш: вид (−1 нет, 0 вперёд, 1 назад, 2 влево, 3 вправо) и время с начала, тики. */
    public int dashKind = -1;
    public double dashAge;

    // ---- руки и предметы
    public boolean leftHanded;
    public boolean mainHandItem, offHandItem;
    /** Взмах рукой начался в этом кадре (true) и какой рукой — основной (main=true). */
    public boolean swingStarted, swingMain = true;
    /** Использование предмета: вид (см. {@link Use}) и рука (main=true). */
    public Use use = Use.NONE;
    public boolean useMain = true;
    /** Заряженный арбалет в руке (держат наизготовку) и в какой (main=true). */
    public boolean crossbowCharged, crossbowMain = true;

    public enum Use {NONE, EAT, DRINK, BLOCK, BOW, SPEAR, CROSSBOW, SPYGLASS, HORN, BRUSH}
}
