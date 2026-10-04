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


# ---- 0–12: Синий появляется в правой руке
key(0)
key(3, rightArm__pitch=-68, rightArm__yaw=-10, rightArm__roll=0, rightArm__bend=-38,
    leftArm__pitch=-12, leftArm__roll=-8, leftArm__bend=-18, head__pitch=14, torso__yaw=-5)
key(7, rightArm__pitch=-74, rightArm__bend=-48, head__pitch=18, torso__bend=4)
# замах: рука уходит вниз-назад, локоть сжат
key(11, ease="OUTQUAD", rightArm__pitch=-38, rightArm__yaw=-4, rightArm__bend=-96, torso__yaw=-8,
    torso__bend=10, head__pitch=6, rightLeg__bend=12, leftLeg__bend=6, body__y=-0.05)
# ---- 13–26: бросок вверх, взгляд провожает Синего
key(14, rightArm__pitch=-172, rightArm__roll=10, rightArm__bend=-4, torso__yaw=3, torso__bend=-8,
    torso__pitch=-3, head__pitch=-32, leftArm__pitch=-6, leftArm__bend=-10, rightLeg__bend=0, leftLeg__bend=0,
    body__y=0.0)
key(19, rightArm__pitch=-160, rightArm__bend=-12, head__pitch=-42, torso__pitch=-2)
key(26, rightArm__pitch=-14, rightArm__yaw=0, rightArm__roll=6, rightArm__bend=-14, torso__yaw=0,
    torso__pitch=0, torso__bend=0, head__pitch=-36)
key(32, head__pitch=-18, rightArm__bend=-10)
# ---- 35–45: Красный загорается у груди (манга-кадры А на 40–45)
key(36, rightArm__pitch=-52, rightArm__yaw=-32, rightArm__roll=4, rightArm__bend=-84, head__pitch=16,
    leftArm__pitch=-22, leftArm__roll=-10, leftArm__bend=-34, torso__bend=6)
key(42, rightArm__pitch=-58, rightArm__bend=-92, head__pitch=20, torso__bend=10)
# ---- 45–58: вихрь — сгорбился, полуприсед, кулак у лица
key(48, rightArm__pitch=-112, rightArm__yaw=-22, rightArm__roll=8, rightArm__bend=-74,
    leftArm__pitch=-34, leftArm__roll=-22, leftArm__bend=-46, torso__bend=24, torso__pitch=4, head__pitch=-4,
    rightLeg__pitch=-22, rightLeg__bend=30, leftLeg__pitch=14, leftLeg__bend=14, body__y=-0.14)
key(55, rightArm__pitch=-118, rightArm__bend=-82, torso__bend=22, leftArm__bend=-50)
# ---- 58–66: Красный плавно подносится к лицу, корпус распрямляется
key(61, rightArm__pitch=-138, rightArm__yaw=-34, rightArm__roll=16, rightArm__bend=-98, torso__bend=2,
    torso__pitch=0, head__pitch=8, leftArm__pitch=-18, leftArm__roll=-14, leftArm__bend=-26,
    rightLeg__pitch=-6, rightLeg__bend=8, leftLeg__pitch=4, leftLeg__bend=4, body__y=-0.03)
# замах перед выстрелом: локоть ещё сильнее согнут
key(65, ease="OUTQUAD", rightArm__pitch=-122, rightArm__bend=-112, head__pitch=0, torso__bend=6)
# ---- 68–80: выстрел вверх, взгляд вверх
key(68, rightArm__pitch=-178, rightArm__yaw=0, rightArm__roll=6, rightArm__bend=-2, head__pitch=-34,
    torso__pitch=-3, torso__bend=-10, leftArm__pitch=-4, leftArm__roll=-12, leftArm__bend=-8,
    rightLeg__pitch=0, rightLeg__bend=0, leftLeg__pitch=0, leftLeg__bend=0, body__y=0.0)
key(75, rightArm__pitch=-166, rightArm__bend=-10, head__pitch=-46)
key(80, rightArm__pitch=-18, rightArm__roll=8, rightArm__bend=-18, leftArm__pitch=-14, leftArm__bend=-18,
    head__pitch=-30, torso__pitch=0, torso__bend=0)
# ---- 82–84: глубокий присед перед прыжком
key(83, ease="OUTQUAD", body__y=-0.42, torso__pitch=8, torso__bend=28, head__pitch=-16,
    rightArm__pitch=42, rightArm__roll=14, rightArm__bend=-24, leftArm__pitch=42, leftArm__roll=-14, leftArm__bend=-24,
    rightLeg__pitch=-52, rightLeg__bend=96, leftLeg__pitch=-46, leftLeg__bend=90)
# ---- 85–98: прыжок с сальто (body pitch 0 → 360), группировка
key(85, body__y=0.0, body__pitch=0, torso__pitch=-2, torso__bend=-6, head__pitch=-20,
    rightArm__pitch=-168, rightArm__roll=16, rightArm__bend=-8, leftArm__pitch=-168, leftArm__roll=-16, leftArm__bend=-8,
    rightLeg__pitch=10, rightLeg__bend=4, leftLeg__pitch=12, leftLeg__bend=6)
key(90, body__pitch=180, torso__bend=26, head__pitch=20,
    rightArm__pitch=-62, rightArm__roll=26, rightArm__bend=-70, leftArm__pitch=-62, leftArm__roll=-26, leftArm__bend=-70,
    rightLeg__pitch=-84, rightLeg__bend=110, leftLeg__pitch=-80, leftLeg__bend=104)
key(96, body__pitch=360, torso__bend=4, head__pitch=0,
    rightArm__pitch=-20, rightArm__roll=50, rightArm__bend=-24, leftArm__pitch=-20, leftArm__roll=-50, leftArm__bend=-24,
    rightLeg__pitch=-8, rightLeg__bend=18, leftLeg__pitch=8, leftLeg__bend=34)
# ---- 98–128: в небе между шарами, «дышит», лицо крупным планом
key(100, torso__bend=0, rightArm__pitch=-14, rightArm__roll=60, rightArm__bend=-20,
    leftArm__pitch=-14, leftArm__roll=-60, leftArm__bend=-20, rightLeg__pitch=-10, rightLeg__bend=22,
    leftLeg__pitch=10, leftLeg__bend=36)
key(107, rightArm__roll=64, leftArm__roll=-64, rightArm__bend=-14, leftArm__bend=-14, torso__pitch=-2, head__pitch=-4)
key(114, rightArm__roll=58, leftArm__roll=-58, rightArm__bend=-28, leftArm__bend=-28, torso__pitch=2, head__pitch=12)
key(121, rightArm__roll=61, leftArm__roll=-61, rightArm__bend=-22, leftArm__bend=-22, head__pitch=14, head__roll=-4)
key(127, head__pitch=10, head__roll=0)
# ---- 128–144: руки вперёд, сводят шары; локти постепенно разгибаются
key(133, rightArm__pitch=-82, rightArm__yaw=-24, rightArm__roll=10, rightArm__bend=-46,
    leftArm__pitch=-82, leftArm__yaw=24, leftArm__roll=-10, leftArm__bend=-46, torso__pitch=3, torso__bend=8, head__pitch=6)
key(141, rightArm__pitch=-88, rightArm__yaw=-30, rightArm__bend=-14, leftArm__pitch=-88, leftArm__yaw=30,
    leftArm__bend=-14, torso__pitch=4, torso__bend=10)
key(144, rightArm__bend=-6, leftArm__bend=-6)
# ---- 150–212: руки раскинуты во вспышке, прогиб назад
key(152, ease="OUTQUAD", rightArm__pitch=-34, rightArm__yaw=0, rightArm__roll=106, rightArm__bend=-12,
    leftArm__pitch=-34, leftArm__yaw=0, leftArm__roll=-106, leftArm__bend=-12, torso__pitch=-4, torso__bend=-16,
    head__pitch=-26, rightLeg__roll=8, leftLeg__roll=-8, rightLeg__bend=24, leftLeg__bend=30)
key(166, rightArm__roll=110, leftArm__roll=-110, rightArm__bend=-8, leftArm__bend=-8, head__pitch=-30, torso__bend=-18)
key(178, rightArm__roll=104, leftArm__roll=-104, rightArm__bend=-16, leftArm__bend=-16, head__pitch=-24)
key(190, rightArm__roll=112, leftArm__roll=-112, rightArm__bend=-6, leftArm__bend=-6, head__pitch=-32, torso__bend=-20)
key(212)

END_TICK = 212


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
