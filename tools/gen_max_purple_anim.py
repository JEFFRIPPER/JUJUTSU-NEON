#!/usr/bin/env python3
"""Анимация тела для кат-сцены Максимального Фиолетового (формат Player Animator / Emotecraft v3).

Результат: src/main/resources/assets/jujutsu_neon/player_animation/max_purple.json

Углы в градусах. Знаки (проверено по коду Player Animator + bendy-lib):
  pitch < 0  — рука/нога вперёд-вверх; head pitch > 0 — взгляд вниз
  rightArm roll > 0 — правая рука наружу; leftArm roll < 0 — левая наружу
  rightArm yaw < 0 — правая рука внутрь, к груди
  bend рук < 0 — сгиб в локте (предплечье вперёд)
  bend ног > 0 — сгиб в колене (голень назад)
  torso bend > 0 — сгорбиться вперёд
  body — всё тело целиком: y в блоках (вверх +), pitch — сальто
Тики совпадают с таймлайном MaximumPurple (см. MaximumPurple.java).
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/player_animation/max_purple.json"
OUT.parent.mkdir(parents=True, exist_ok=True)

CHANNELS = {
    "head": ["pitch", "yaw", "roll"],
    "torso": ["pitch", "yaw", "roll", "bend"],
    "rightArm": ["pitch", "yaw", "roll", "bend"],
    "leftArm": ["pitch", "yaw", "roll", "bend"],
    "rightLeg": ["pitch", "yaw", "roll", "bend"],
    "leftLeg": ["pitch", "yaw", "roll", "bend"],
    "body": ["y", "pitch"],
}

NEUTRAL = {f"{p}.{c}": 0.0 for p, cs in CHANNELS.items() for c in cs}
NEUTRAL.update({"rightArm.bend": -6, "leftArm.bend": -6, "rightArm.roll": 4, "leftArm.roll": -4})

# (тик, изменения позы относительно предыдущей, сглаживание сегмента, который начинается с этого кадра)
KEYS = []


def key(tick, ease="INOUTSINE", **changes):
    KEYS.append((tick, {k.replace("__", "."): v for k, v in changes.items()}, ease))


# ---- 0–12 (0–0,6 с): разворот, правая рука поднимается
key(0)
key(4, rightArm__pitch=-96, rightArm__yaw=-12, rightArm__roll=18, rightArm__bend=-34, torso__yaw=-8,
    head__pitch=-4, leftArm__pitch=-10, leftArm__roll=-10, leftArm__bend=-14)
key(9, rightArm__pitch=-158, rightArm__yaw=-24, rightArm__roll=22, rightArm__bend=-58, torso__yaw=-14,
    torso__bend=-4, head__pitch=-16, leftArm__pitch=-18, leftArm__roll=-16, leftArm__bend=-22,
    rightLeg__bend=8, leftLeg__bend=4)
# ---- 13–14: Синий вспыхивает в поднятой руке
key(13, rightArm__pitch=-168, rightArm__bend=-40, head__pitch=-22, torso__bend=-6)
# ---- 16–38 (0,8–1,9 с): присед с закруткой, рука вращает Синего вокруг себя
key(17, ease="OUTQUAD", rightArm__pitch=-118, rightArm__yaw=-46, rightArm__roll=34, rightArm__bend=-78,
    torso__yaw=-22, torso__bend=16, head__pitch=8, leftArm__pitch=-36, leftArm__yaw=18, leftArm__roll=-26,
    leftArm__bend=-48, rightLeg__pitch=-26, rightLeg__bend=42, leftLeg__pitch=12, leftLeg__bend=24, body__y=-0.2)
SW = [(21, -64, 6, 10, -36, 14, 12, 0.24), (25, -126, -50, 36, -84, -20, 20, 0.18),
      (29, -58, 10, 8, -32, 16, 14, 0.26), (33, -132, -54, 38, -88, -24, 22, 0.2)]
for t, ap, ay, ar, ab, ty, tb, dy in SW:
    key(t, rightArm__pitch=ap, rightArm__yaw=ay, rightArm__roll=ar, rightArm__bend=ab, torso__yaw=ty,
        torso__bend=tb, body__y=-dy, head__pitch=4 if ap > -90 else 10,
        leftArm__pitch=-30 if ap > -90 else -42, leftArm__bend=-40 if ap > -90 else -56)
# ---- 37–39: замах назад, глубокий присед
key(38, ease="OUTQUAD", rightArm__pitch=34, rightArm__yaw=8, rightArm__roll=28, rightArm__bend=-46,
    torso__yaw=26, torso__bend=22, torso__pitch=4, head__pitch=-6, leftArm__pitch=-52, leftArm__yaw=26,
    leftArm__bend=-60, rightLeg__pitch=-40, rightLeg__bend=70, leftLeg__pitch=-6, leftLeg__bend=46, body__y=-0.36)
# ---- 40–46 (2,0–2,3 с): бросок вверх, корпус раскручивается, взгляд вверх
key(41, rightArm__pitch=-176, rightArm__yaw=-6, rightArm__roll=14, rightArm__bend=-4, torso__yaw=-18,
    torso__bend=-12, torso__pitch=-4, head__pitch=-44, leftArm__pitch=8, leftArm__yaw=0, leftArm__roll=-20,
    leftArm__bend=-12, rightLeg__pitch=6, rightLeg__bend=4, leftLeg__pitch=8, leftLeg__bend=6, body__y=0.06)
key(46, rightArm__pitch=-150, rightArm__bend=-14, torso__yaw=-10, torso__bend=-6, head__pitch=-52,
    body__y=0.0)
key(53, rightArm__pitch=-40, rightArm__yaw=-4, rightArm__roll=10, rightArm__bend=-22, torso__yaw=-2,
    torso__bend=0, torso__pitch=0, head__pitch=-30, leftArm__pitch=-10, leftArm__roll=-10, leftArm__bend=-16,
    rightLeg__pitch=0, rightLeg__bend=6, leftLeg__pitch=0, leftLeg__bend=4)
# ---- 56–65 (2,8–3,25 с): спокойно, кисть плавно подносится к лицу, в ладони загорается Красный
key(58, rightArm__pitch=-96, rightArm__yaw=-40, rightArm__roll=8, rightArm__bend=-104, head__pitch=6,
    torso__bend=4, torso__yaw=-6)
key(62, rightArm__pitch=-106, rightArm__yaw=-46, rightArm__bend=-116, head__pitch=10, torso__bend=6)
key(65, rightArm__pitch=-102, rightArm__bend=-120, head__pitch=8)
# ---- 68: короткий замах вниз, 69–72: рука вверх — выстрел
key(68, ease="OUTQUAD", rightArm__pitch=-70, rightArm__yaw=-20, rightArm__bend=-96, torso__bend=10,
    head__pitch=0, rightLeg__bend=16, leftLeg__bend=12, body__y=-0.08)
key(70, rightArm__pitch=-178, rightArm__yaw=-2, rightArm__roll=8, rightArm__bend=-2, torso__bend=-12,
    torso__pitch=-3, head__pitch=-48, leftArm__pitch=6, leftArm__roll=-22, leftArm__bend=-10,
    rightLeg__bend=2, leftLeg__bend=2, body__y=0.04)
key(76, rightArm__pitch=-170, rightArm__bend=-8, head__pitch=-56, torso__bend=-8, body__y=0.0)
# ---- 80–226: опускает руку, смотрит в небо, где гоняются шары («дышит»)
key(88, rightArm__pitch=-24, rightArm__yaw=0, rightArm__roll=10, rightArm__bend=-18, torso__bend=-4,
    torso__pitch=0, head__pitch=-46, leftArm__pitch=-8, leftArm__roll=-12, leftArm__bend=-14)
for i, t in enumerate(range(100, 226, 14)):
    sgn = 1 if i % 2 == 0 else -1
    key(t, head__pitch=-46 + sgn * 4, head__yaw=sgn * 6, rightArm__roll=10 + sgn * 3,
        leftArm__roll=-12 + sgn * 3, torso__bend=-4 + sgn * 2)
# ---- 228–282 (11,4–14,1 с): левитация — подъём к шарам
key(232, head__pitch=-30, head__yaw=0, rightArm__pitch=-12, rightArm__roll=34, rightArm__bend=-24,
    leftArm__pitch=-12, leftArm__roll=-34, leftArm__bend=-24, torso__bend=6, rightLeg__pitch=-8,
    rightLeg__bend=26, leftLeg__pitch=10, leftLeg__bend=40, rightLeg__roll=2, leftLeg__roll=-2)
key(244, rightArm__roll=46, leftArm__roll=-46, rightArm__bend=-30, leftArm__bend=-30, head__pitch=-26,
    torso__bend=2, rightLeg__bend=30, leftLeg__bend=44)
key(256, rightArm__roll=40, leftArm__roll=-40, rightArm__bend=-22, leftArm__bend=-22, head__pitch=-34,
    torso__bend=-2, rightLeg__bend=24, leftLeg__bend=38)
# ---- 266–282: кольцо раскрывается — руки в стороны, тянет энергию
key(270, ease="OUTQUAD", rightArm__pitch=-30, rightArm__roll=92, rightArm__bend=-16, leftArm__pitch=-30,
    leftArm__roll=-92, leftArm__bend=-16, head__pitch=-22, torso__bend=-10, torso__pitch=-3)
key(282, rightArm__roll=98, leftArm__roll=-98, rightArm__bend=-10, leftArm__bend=-10, head__pitch=-28,
    torso__bend=-12)
# ---- 283–349: темнота и космос (игрока не видно) — держим позу, к концу собирается
key(330, rightArm__roll=88, leftArm__roll=-88, head__pitch=-20)
key(346, rightArm__pitch=-40, rightArm__yaw=-20, rightArm__roll=30, rightArm__bend=-60, leftArm__pitch=-40,
    leftArm__yaw=20, leftArm__roll=-30, leftArm__bend=-60, torso__bend=10, torso__pitch=0, head__pitch=0,
    rightLeg__bend=30, leftLeg__bend=34)
# ---- 350–358 (17,5–17,9 с): со спины — рука поднимается над головой, замах
key(352, rightArm__pitch=-60, rightArm__bend=-70, leftArm__pitch=-30, leftArm__bend=-50, torso__bend=12)
key(357, ease="OUTQUAD", rightArm__pitch=-156, rightArm__yaw=-34, rightArm__roll=26, rightArm__bend=-96,
    leftArm__pitch=-56, leftArm__yaw=34, leftArm__roll=-20, leftArm__bend=-86, torso__yaw=14, torso__bend=-6,
    head__pitch=-8, rightLeg__pitch=-14, rightLeg__bend=24, leftLeg__pitch=14, leftLeg__bend=14)
# ---- 360–364 (18,0–18,2 с): поза Годжо — правая рука вперёд, левая согнута у груди, широкая стойка
key(360, rightArm__pitch=-92, rightArm__yaw=-4, rightArm__roll=4, rightArm__bend=-4, leftArm__pitch=-64,
    leftArm__yaw=52, leftArm__roll=-6, leftArm__bend=-108, torso__yaw=-16, torso__bend=4, torso__pitch=2,
    head__pitch=4, head__yaw=8, rightLeg__pitch=-22, rightLeg__roll=12, rightLeg__bend=22,
    leftLeg__pitch=18, leftLeg__roll=-12, leftLeg__bend=12)
key(363, rightArm__pitch=-96, torso__yaw=-20, torso__bend=8, rightArm__bend=-2)
key(380, rightArm__pitch=-94, torso__yaw=-18, torso__bend=6)
key(408)

END_TICK = 408


def build():
    pose = dict(NEUTRAL)
    moves = []
    for tick, changes, ease in KEYS:
        pose.update(changes)
        by_part = {}
        for name, value in pose.items():
            part, ch = name.split(".")
            by_part.setdefault(part, {})[ch] = round(float(value), 3)
        move = {"tick": tick, "easing": "EASE" + ease, "turn": 0}
        move.update(by_part)
        moves.append(move)
    return {
        "version": 3,
        "name": "max_purple",
        "author": "Jujutsu Neon",
        "description": "Maximum Purple cutscene body animation",
        "emote": {
            "beginTick": 0,
            "endTick": END_TICK,
            "stopTick": END_TICK + 10,
            "isLoop": False,
            "returnTick": 0,
            "nsfw": False,
            "degrees": True,
            "moves": moves,
        },
    }


OUT.write_text(json.dumps(build(), indent=1))
print(OUT, OUT.stat().st_size, "bytes,", len(KEYS), "keyframes")
