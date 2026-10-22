#!/usr/bin/env python3
"""Генератор анимаций движения (assets/jujutsu_neon/body_anims/loco_*.json, fly_*.json).

Циклы шагов задаются одной ногой и одной рукой — вторая сторона получается сдвигом фазы на 0,5
и зеркалом (yaw, roll → −yaw, −roll). Фаза 0 — правая нога впереди, касается земли.

Знаки (как в игре): конечность pitch < 0 — вперёд; сгиб ноги > 0 — колено назад; сгиб руки < 0 — локоть;
правая рука/нога roll > 0 — наружу; корпус pitch > 0 — наклон вперёд, yaw > 0 — поворот вправо,
roll > 0 — наклон влево; всё тело (body) pitch > 0 — назад, roll > 0 — влево, y — блоки.

    python3 tools/anim-preview/gen_locomotion.py
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/jujutsu_neon/body_anims"


def shift(keys, d):
    """Сдвиг ключей цикла по фазе (0..1) с переносом через конец."""
    out = []
    for t, v in keys:
        out.append(((t + d) % 1.0, v))
    out.sort(key=lambda x: x[0])
    return out


def mirror_limb(v):
    p, y, r, b = v
    return [p, -y, -r, b]


def cycle(name, right_leg, right_arm, extra, length=1.0):
    """extra: {"кость.канал" или "кость": [[фаза, значение], ...]}"""
    tracks = {}

    def put_limb(joint, keys):
        for ch, idx in (("pitch", 0), ("yaw", 1), ("roll", 2), ("bend", 3)):
            tracks[f"{joint}.{ch}"] = [[round(t * length, 4), round(v[idx], 3)] for t, v in keys]

    put_limb("rightLeg", right_leg)
    put_limb("leftLeg", [(t, mirror_limb(v)) for t, v in shift(right_leg, 0.5)])
    put_limb("rightArm", right_arm)
    put_limb("leftArm", [(t, mirror_limb(v)) for t, v in shift(right_arm, 0.5)])
    for k, keys in extra.items():
        tracks[k] = [[round(t * length, 4), round(v, 4)] for t, v in keys]
    doc = {
        "format": "jn_anim",
        "description": f"Цикл движения {name}: фаза 0 — правая нога впереди. Сгенерировано gen_locomotion.py.",
        "time": "seconds",
        "length": length,
        "loop": True,
        "tracks": tracks,
    }
    write(name, doc)


def pose_clip(name, length, keys, loop=False, additive=False, ease="smooth", desc=""):
    """keys: [(t, {"кость": [p, y, r, (bend)], "кость.канал": v}, ease?)]"""
    out = []
    for k in keys:
        t, pose = k[0], k[1]
        entry = {"t": t, "pose": pose}
        if len(k) > 2:
            entry["ease"] = k[2]
        out.append(entry)
    doc = {"format": "jn_anim", "description": desc, "time": "seconds", "length": length, "loop": loop,
           "ease": ease, "keys": out}
    if additive:
        doc["additive"] = True
    write(name, doc)


def write(name, doc):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- ходьба (до ~3 блоков/с)
cycle("loco_walk",
      right_leg=[(0.000, [-26, 0, 1, 4]), (0.125, [-16, 0, 1, 12]), (0.250, [0, 0, 1, 6]),
                 (0.375, [14, 0, 1, 8]), (0.500, [24, 0, 1, 16]), (0.625, [14, 0, 1, 40]),
                 (0.750, [-8, 0, 1, 46]), (0.875, [-24, 0, 1, 20])],
      right_arm=[(0.00, [20, 0, 4, -10]), (0.25, [2, 0, 4, -12]), (0.50, [-22, -3, 5, -20]), (0.75, [0, 0, 4, -12])],
      extra={
          "torso.pitch": [(0, 3.5), (0.12, 4.5), (0.37, 2.8), (0.5, 3.5), (0.62, 4.5), (0.87, 2.8)],
          "torso.yaw": [(0, 5), (0.5, -5)],
          "torso.roll": [(0.25, -1.5), (0.75, 1.5)],
          "body.y": [(0, 0), (0.3, 0.008), (0.5, 0), (0.8, 0.008)],
          "body.roll": [(0.25, 0.8), (0.75, -0.8)],
          "head.pitch": [(0, 1), (0.25, -0.5), (0.5, 1), (0.75, -0.5)],
      })

# ---------------------------------------------------------------- бег (обычная скорость в повязке, 6 блоков/с)
cycle("loco_run",
      right_leg=[(0.00, [-40, 0, 2, 22]), (0.10, [-24, 0, 2, 34]), (0.25, [6, 0, 2, 22]), (0.38, [30, 0, 2, 18]),
                 (0.50, [40, 0, 2, 42]), (0.62, [26, 0, 2, 95]), (0.75, [-24, 0, 2, 100]), (0.88, [-50, 0, 2, 58])],
      right_arm=[(0.00, [42, 4, 8, -72]), (0.25, [0, 0, 8, -80]), (0.50, [-58, -8, 6, -100]), (0.75, [-6, 0, 8, -85])],
      extra={
          "torso.pitch": [(0, 10), (0.1, 12), (0.35, 9), (0.5, 10), (0.6, 12), (0.85, 9)],
          "torso.yaw": [(0, 9), (0.5, -9)],
          "torso.roll": [(0.25, -2), (0.75, 2)],
          "body.y": [(0.0, 0), (0.22, 0), (0.42, 0.045), (0.5, 0), (0.72, 0), (0.92, 0.045)],
          "body.pitch": [(0, -3)],
          "body.roll": [(0.25, 1.2), (0.75, -1.2)],
          "head.pitch": [(0, -2), (0.1, 0), (0.35, -3), (0.5, -2), (0.6, 0), (0.85, -3)],
      })

# ---------------------------------------------------------------- сверхбег (Ctrl, 24 блока/с): сильный наклон, руки качают
cycle("loco_sprint",
      right_leg=[(0.00, [-62, 0, 3, 34]), (0.10, [-40, 0, 3, 40]), (0.25, [10, 0, 3, 26]), (0.38, [42, 0, 3, 22]),
                 (0.50, [56, 0, 3, 60]), (0.62, [34, 0, 3, 125]), (0.75, [-36, 0, 3, 125]), (0.88, [-70, 0, 3, 70])],
      right_arm=[(0.00, [62, 6, 12, -80]), (0.25, [0, 0, 10, -95]), (0.50, [-82, -10, 8, -115]), (0.75, [-6, 0, 10, -95])],
      extra={
          "torso.pitch": [(0, 22), (0.1, 25), (0.35, 20), (0.5, 22), (0.6, 25), (0.85, 20)],
          "torso.yaw": [(0, 12), (0.5, -12)],
          "torso.roll": [(0.25, -2.5), (0.75, 2.5)],
          "body.y": [(0.0, 0), (0.2, 0), (0.42, 0.07), (0.5, 0), (0.7, 0), (0.92, 0.07)],
          "body.pitch": [(0, -12)],
          "body.roll": [(0.25, 1.5), (0.75, -1.5)],
          "head.pitch": [(0, -14), (0.25, -16), (0.5, -14), (0.75, -16)],
      })

# ---------------------------------------------------------------- покой: дыхание и лёгкое переминание
pose_clip("loco_idle", 4.0, [
    (0.0, {"torso.pitch": 1.0, "body.y": 0.0, "body.roll": 0.5, "head.pitch": 0.0,
           "rightArm": [1, 0, 3.5, -4], "leftArm": [1, 0, -3.5, -4],
           "rightLeg": [0, 0, 1.5, 1], "leftLeg": [0, 0, -1.5, 1]}),
    (2.0, {"torso.pitch": 2.2, "body.y": -0.006, "body.roll": -0.5, "head.pitch": 1.2,
           "rightArm": [-1, 0, 5, -6], "leftArm": [-1, 0, -5, -6]}),
], loop=True, desc="Покой: дыхание, руки чуть расходятся, вес переносится с ноги на ногу.")

# ---------------------------------------------------------------- присед (Shift)
pose_clip("loco_sneak_idle", 3.0, [
    (0.0, {"torso.pitch": 24, "head.pitch": 0,
           "rightLeg": [-36, 0, 4, 72], "leftLeg": [-30, 0, -4, 62],
           "rightArm": [-20, 0, 7, -30], "leftArm": [-20, 0, -7, -30]}),
    (1.5, {"torso.pitch": 25.5, "rightArm": [-22, 0, 8, -33], "leftArm": [-22, 0, -8, -33]}),
], loop=True, desc="Присед на месте: колени согнуты, корпус вперёд, руки перед собой.")

cycle("loco_sneak_walk",
      right_leg=[(0.00, [-50, 0, 4, 64]), (0.25, [-30, 0, 4, 66]), (0.50, [-12, 0, 4, 70]), (0.75, [-40, 0, 4, 92])],
      right_arm=[(0.00, [-12, 0, 7, -28]), (0.50, [-28, 0, 7, -34])],
      extra={
          "body.y": [(0.0, 0), (0.25, 0.012), (0.5, 0), (0.75, 0.012)],
          "torso.pitch": [(0, 25)],
          "torso.yaw": [(0, 4), (0.5, -4)],
      })

# ---------------------------------------------------------------- воздух: 0 — толчок вверх, 0,5 — верхняя точка, 1 — падение
pose_clip("loco_air", 1.0, [
    (0.0, {"rightLeg": [-38, 0, 3, 72], "leftLeg": [16, 0, -3, 40],
           "rightArm": [-34, 0, 18, -26], "leftArm": [-28, 0, -18, -24],
           "torso.pitch": 6, "body.pitch": -2, "head.pitch": 0}),
    (0.5, {"rightLeg": [-26, 0, 4, 52], "leftLeg": [8, 0, -4, 34],
           "rightArm": [-14, 0, 30, -20], "leftArm": [-10, 0, -30, -20],
           "torso.pitch": 4, "body.pitch": 0}),
    (1.0, {"rightLeg": [-14, 0, 6, 24], "leftLeg": [10, 0, -6, 18],
           "rightArm": [-24, 0, 58, -18], "leftArm": [-20, 0, -58, -18],
           "torso.pitch": -2, "body.pitch": 2}),
], desc="Поза в воздухе по вертикальной скорости (не по времени).")

# ---------------------------------------------------------------- приземление (добавочно): просадка в коленях и отдача
pose_clip("loco_land", 0.42, [
    (0.0, {"body.y": 0, "torso.pitch": 0, "rightLeg": [0, 0, 0, 0], "leftLeg": [0, 0, 0, 0],
           "rightArm": [0, 0, 0, 0], "leftArm": [0, 0, 0, 0], "head.pitch": 0}),
    (0.07, {"body.y": 0, "torso.pitch": 14, "rightLeg": [-18, 0, 0, 36], "leftLeg": [-16, 0, 0, 32],
            "rightArm": [-14, 0, 6, -12], "leftArm": [-14, 0, -6, -12], "head.pitch": 3}, "out"),
    (0.2, {"body.y": 0, "torso.pitch": 6, "rightLeg": [-8, 0, 0, 16], "leftLeg": [-7, 0, 0, 14],
           "rightArm": [-6, 0, 3, -6], "leftArm": [-6, 0, -3, -6], "head.pitch": 1}),
    (0.42, {"body.y": 0, "torso.pitch": 0, "rightLeg": [0, 0, 0, 0], "leftLeg": [0, 0, 0, 0],
            "rightArm": [0, 0, 0, 0], "leftArm": [0, 0, 0, 0], "head.pitch": 0}),
], additive=True, desc="Приземление: прибавляется к позе движения, сила — от скорости падения.")

# ---------------------------------------------------------------- зарядка прыжка (держать пробел)
CROUCH = {"body.y": 0, "body.pitch": -7, "torso.pitch": 18,
          "rightLeg": [-44, 0, 3, 84], "leftLeg": [-40, 0, -3, 80],
          "rightArm": [30, 0, 7, -18], "leftArm": [30, 0, -7, -18]}
pose_clip("loco_charge", 1.2, [
    (0.0, {"body.y": 0, "body.pitch": 0, "torso.pitch": 2, "rightLeg": [0, 0, 1, 2], "leftLeg": [0, 0, -1, 2],
           "rightArm": [0, 0, 4, -6], "leftArm": [0, 0, -4, -6]}),
    (0.75, CROUCH),
    (1.2, {**CROUCH, "rightArm": [32, 0, 8, -20], "leftArm": [32, 0, -8, -20]}),
], desc="Присед перед заряженным прыжком: колени сгибаются, руки уходят назад для замаха.")

# ---------------------------------------------------------------- взлёт (заряженный прыжок)
UP = {"body.y": 0, "body.pitch": -4, "torso.pitch": -5, "head.pitch": -12,
      "rightLeg": [8, 0, 2, 4], "leftLeg": [-2, 0, -2, 6],
      "rightArm": [-164, 0, 18, -10], "leftArm": [-164, 0, -18, -10]}
RISE = {"body.y": 0, "body.pitch": 3, "torso.pitch": 1, "head.pitch": 0,
        "rightLeg": [16, 0, 4, 32], "leftLeg": [-6, 0, -3, 16],
        "rightArm": [-30, 0, 38, -22], "leftArm": [-26, 0, -40, -20]}
pose_clip("loco_leap", 1.2, [
    (0.0, {**CROUCH, "head.pitch": 0}),
    (0.15, UP, "out"),
    (0.55, RISE),
    (1.2, RISE),
], desc="Взлёт: выстрел вверх с руками над головой, потом руки раскрываются.")

# ---------------------------------------------------------------- полёт
HOVER = {"torso.pitch": 1.5, "head.pitch": -2, "rightLeg": [16, 0, 4, 34], "leftLeg": [-6, 0, -3, 14]}
pose_clip("fly_hover", 2.0, [
    (0.0, {**HOVER, "body.pitch": 4, "body.y": 0, "rightArm": [-22, 0, 24, -22], "leftArm": [-18, 0, -26, -18]}),
    (0.5, {"body.pitch": 5, "body.y": 0.04, "rightArm": [-22, 0, 26, -22], "leftArm": [-18, 0, -28, -18]}),
    (1.0, {"body.pitch": 4, "body.y": 0, "rightArm": [-22, 0, 24, -22], "leftArm": [-18, 0, -26, -18]}),
    (1.5, {"body.pitch": 3, "body.y": -0.04, "rightArm": [-22, 0, 22, -22], "leftArm": [-18, 0, -24, -18]}),
], loop=True, desc="Зависание: плавное покачивание вверх-вниз.")

pose_clip("fly_glide", 2.0, [
    (0.0, {"torso.pitch": 2.5, "rightLeg": [22, 0, 3, 26], "leftLeg": [10, 0, -3, 16],
           "rightArm": [22, 0, 20, -16], "leftArm": [24, 0, -20, -16]}),
    (1.0, {"rightLeg": [24, 0, 3, 30], "leftLeg": [8, 0, -3, 14],
           "rightArm": [24, 0, 22, -18], "leftArm": [22, 0, -22, -14]}),
], loop=True, desc="Полёт: тело вдоль полёта (наклон считает движок), руки назад.")

pose_clip("fly_boost", 1.0, [
    (0.0, {"torso.pitch": -2.4, "rightArm": [-172, 0, 6, -4], "leftArm": [14, 0, -14, -6],
           "rightLeg": [6, 0, 3, 6], "leftLeg": [12, 0, -3, 18]}),
    (0.5, {"rightLeg": [8, 0, 3, 8], "leftLeg": [10, 0, -3, 14]}),
], loop=True, desc="Ускорение в полёте: «супермен», правый кулак вперёд.")

pose_clip("fly_land", 1.0, [
    (0.0, {"body.pitch": 6, "torso.pitch": 3.6, "rightLeg": [-6, 0, 4, 10], "leftLeg": [4, 0, -4, 8],
           "rightArm": [-28, 0, 36, -18], "leftArm": [-24, 0, -36, -18]}),
], loop=True, desc="Посадка из полёта: ноги вниз, руки в стороны.")

print("ok", OUT)
