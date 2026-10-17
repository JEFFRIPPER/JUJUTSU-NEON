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
    "body": ["y", "pitch", "yaw", "roll"],
}

NEUTRAL = {f"{p}.{c}": 0.0 for p, cs in CHANNELS.items() for c in cs}
NEUTRAL.update({"rightArm.bend": -6, "leftArm.bend": -6, "rightArm.roll": 4, "leftArm.roll": -4})

# (тик, изменения позы относительно предыдущей, сглаживание сегмента, который начинается с этого кадра)
KEYS = []


def key(tick, ease="INOUTSINE", **changes):
    KEYS.append((tick, {k.replace("__", "."): v for k, v in changes.items()}, ease))


# ---- 0–72,7 (0–3,63 с): по референсу 120 к/с покадрово (docs/max_purple_frames.md, сверка ref↔мод).
# Номер кадра референса (120 к/с) → тик катсцены: опорные склейки референса привязаны к тикам мода.
_REF = [(0, 2.67), (58, 40 / 3), (228, 128 / 3), (374, 66.0), (384, 206 / 3), (415, 218 / 3)]


def rf(n):
    """Кадр нового референса (120 к/с) → тик катсцены."""
    for (a, ta), (b, tb) in zip(_REF, _REF[1:]):
        if n <= b:
            return round(ta + (tb - ta) * (n - a) / (b - a), 2)
    return _REF[-1][1]


key(0, head__pitch=18, head__yaw=-10, torso__bend=6, rightLeg__bend=10, leftLeg__bend=8, body__y=-0.04,
    rightArm__pitch=-6, leftArm__pitch=-4)
# #0–23: лёгкий присед, голова вниз-вбок, руки вдоль тела, покачивание, правое колено вперёд
key(rf(12), head__pitch=16, head__yaw=-6, torso__roll=3, rightLeg__pitch=-8, rightLeg__bend=14)
key(rf(23), head__pitch=14, head__yaw=-4, torso__roll=-2, rightLeg__pitch=-14, rightLeg__bend=18, leftLeg__bend=10)
# #24–47: правая рука вбок-вверх (плечо 90→120°, локоть ~30°), корпус скручивается влево
key(rf(36), rightArm__pitch=-20, rightArm__roll=95, rightArm__bend=-30, torso__yaw=10, head__pitch=4,
    head__yaw=6, rightLeg__pitch=-6, rightLeg__bend=10)
key(rf(46), rightArm__pitch=-70, rightArm__roll=110, rightArm__bend=-28, torso__yaw=12, head__pitch=-10)
# #48–67: правая рука прямо вверх, взгляд на кисть — вокруг неё вспыхивает кольцо (Синий)
key(rf(52), ease="OUTQUAD", rightArm__pitch=-172, rightArm__roll=12, rightArm__bend=-4, torso__yaw=6,
    torso__bend=-4, head__pitch=-28, head__yaw=4, leftArm__pitch=-6, leftArm__roll=-10)
key(rf(66), rightArm__pitch=-168, rightArm__roll=14, rightArm__bend=-10, head__pitch=-24)
# #72–95: присед, корпус вперёд, вихрь вокруг верха тела; правая рука согнута над головой
key(rf(74), ease="OUTQUAD", body__y=-0.22, rightLeg__pitch=-30, rightLeg__bend=55, leftLeg__pitch=-24,
    leftLeg__bend=48, torso__bend=26, torso__yaw=0, torso__roll=0, head__pitch=10, head__yaw=0,
    rightArm__pitch=-150, rightArm__roll=40, rightArm__bend=-70, leftArm__pitch=-30, leftArm__roll=-14,
    leftArm__bend=-20)
key(rf(94), torso__bend=24, head__pitch=4, rightArm__pitch=-155, rightArm__bend=-90)
# #96–143: глубокий присед-выпад (левое колено вперёд), корпус вперёд ~30°, голова к камере;
#          правая рука у головы (локоть вверх-вбок, кисть за головой), левая вытянута вниз-вперёд
key(rf(104), ease="OUTQUAD", body__y=-0.34, leftLeg__pitch=-46, leftLeg__bend=70, rightLeg__pitch=22,
    rightLeg__bend=36, torso__bend=28, head__pitch=-14, rightArm__pitch=-158, rightArm__roll=44,
    rightArm__bend=-115, leftArm__pitch=-38, leftArm__roll=-16, leftArm__bend=-6)
key(rf(140), torso__bend=30, head__pitch=-16, rightArm__bend=-118, leftArm__pitch=-42)
# #144–167: левая рука горизонтально вбок-вперёд (плечо ~80°, локоть прямой), правая согнута у головы
key(rf(150), leftArm__pitch=-34, leftArm__roll=-78, leftArm__bend=-2, leftArm__yaw=6, torso__yaw=-8)
key(rf(166), leftArm__pitch=-36, leftArm__roll=-80, torso__yaw=-10)
# #168–191: разворот — левое плечо к камере (видна спина сбоку), левая рука вверх-вперёд к камере
key(rf(178), ease="INOUTQUAD", body__yaw=-48, leftArm__pitch=-128, leftArm__roll=-20, leftArm__bend=-22,
    torso__yaw=-6, head__yaw=20, head__pitch=0, body__y=-0.24, leftLeg__pitch=-30, leftLeg__bend=50,
    rightLeg__pitch=10, rightLeg__bend=30)
key(rf(190), body__yaw=-52, leftArm__pitch=-136)
# #192–215: обратно лицом к камере; левая кисть перед камерой внизу, правая рука прижата к корпусу
key(rf(200), ease="INOUTQUAD", body__yaw=0, leftArm__pitch=-72, leftArm__roll=-6, leftArm__bend=-62,
    rightArm__pitch=-12, rightArm__roll=6, rightArm__bend=-34, rightArm__yaw=0, head__yaw=0, torso__yaw=0,
    torso__bend=16, head__pitch=-6)
key(rf(214), leftArm__pitch=-78, leftArm__bend=-70)
# #216–227: замах — корпус влево (правое плечо вперёд), правая рука горизонтально вбок-назад,
#           левая согнута перед грудью
key(rf(220), ease="OUTQUAD", body__yaw=40, rightArm__pitch=8, rightArm__roll=92, rightArm__yaw=30,
    rightArm__bend=-6, leftArm__pitch=-80, leftArm__yaw=40, leftArm__roll=-4, leftArm__bend=-92,
    torso__yaw=8, torso__bend=10, head__yaw=-30)
key(rf(227), body__yaw=46, rightArm__yaw=36, torso__yaw=10)
# #228–230: бросок — корпус разворачивается вправо, правая рука проносится вперёд
key(rf(230), ease="OUTQUAD", body__yaw=-34, rightArm__pitch=-92, rightArm__yaw=-38, rightArm__roll=26,
    rightArm__bend=-4, torso__yaw=-10, head__yaw=12, leftArm__pitch=-30, leftArm__yaw=10, leftArm__bend=-40)
# #237–255: выпрямляется, руки к груди
key(rf(250), ease="INOUTSINE", body__yaw=0, body__y=-0.04, leftLeg__pitch=0, leftLeg__bend=8, rightLeg__pitch=0,
    rightLeg__bend=8, torso__bend=4, torso__yaw=0, head__yaw=0, head__pitch=0,
    rightArm__pitch=-52, rightArm__yaw=-30, rightArm__roll=6, rightArm__bend=-82,
    leftArm__pitch=-42, leftArm__yaw=28, leftArm__roll=-6, leftArm__bend=-72)
# #256–279: (камера сзади) поворачивается; правая рука согнута, кисть у груди/лица
key(rf(270), rightArm__pitch=-82, rightArm__yaw=-24, rightArm__bend=-104, body__yaw=-10)
# #280–287: обе руки перед грудью, правое предплечье горизонтально (кисть у левого плеча)
key(rf(284), rightArm__pitch=-76, rightArm__yaw=-52, rightArm__bend=-92, leftArm__pitch=-32, leftArm__yaw=20,
    leftArm__bend=-70, body__yaw=0, head__pitch=6)
# #288–319: правое плечо к камере; ЛЕВОЕ предплечье вертикально перед лицом, правая рука у живота
key(rf(304), ease="INOUTQUAD", body__yaw=-26, leftArm__pitch=-84, leftArm__yaw=34, leftArm__roll=-2,
    leftArm__bend=-112, rightArm__pitch=-36, rightArm__yaw=-30, rightArm__bend=-82, head__pitch=2)
# #320–414: левое предплечье перед лицом, правая кисть у подбородка — в ней рождается Красный
key(rf(330), rightArm__pitch=-70, rightArm__yaw=-40, rightArm__bend=-120, leftArm__pitch=-88, leftArm__bend=-116)
key(rf(372), rightArm__pitch=-72, rightArm__bend=-122, leftArm__pitch=-90)
key(rf(386), ease="OUTQUAD", rightArm__pitch=-78, rightArm__bend=-110, torso__bend=0, head__pitch=-2,
    body__y=0.02)
key(rf(414), rightArm__pitch=-74, rightArm__bend=-118, body__y=-0.02)
# ---- 80–226: опускает руку, смотрит в небо, где гоняются шары («дышит»)
key(88, rightArm__pitch=-24, rightArm__yaw=0, rightArm__roll=10, rightArm__bend=-18, torso__bend=-4,
    torso__pitch=0, head__pitch=-46, leftArm__pitch=-8, leftArm__roll=-12, leftArm__bend=-14)
for i, t in enumerate(range(100, 226, 14)):
    sgn = 1 if i % 2 == 0 else -1
    key(t, head__pitch=-46 + sgn * 4, head__yaw=sgn * 6, rightArm__roll=10 + sgn * 3,
        leftArm__roll=-12 + sgn * 3, torso__bend=-4 + sgn * 2)
# ---- 228–282 (11,4–14,1 с): подъём к шарам в сомкнутой позе — колени подобраны, руки скрещены у груди
key(240, head__pitch=18, head__yaw=0, torso__bend=26, torso__pitch=0, rightArm__pitch=-62, rightArm__yaw=-46,
    rightArm__roll=6, rightArm__bend=-104, leftArm__pitch=-62, leftArm__yaw=46, leftArm__roll=-6, leftArm__bend=-104,
    rightLeg__pitch=-64, rightLeg__bend=98, rightLeg__roll=2, leftLeg__pitch=-70, leftLeg__bend=104, leftLeg__roll=-2)
key(254, torso__bend=28, head__pitch=20, rightLeg__pitch=-68, leftLeg__pitch=-66)
key(268, torso__bend=25, head__pitch=17, rightLeg__pitch=-62, leftLeg__pitch=-72)
key(282, torso__bend=27, head__pitch=19, rightLeg__pitch=-66, leftLeg__pitch=-68)
# ---- 283–349: темнота и космос (игрока не видно) — сжимается сильнее
key(330, torso__bend=28, head__pitch=20)
# ---- 349–366 (17,45–18,3 с): финал по референсу 120 к/с (#2081–2186), тик ≈ #/6 + 2.
# #2081–2119: голова опущена вперёд, руки скрещены перед грудью/лицом
key(348, rightArm__pitch=-112, rightArm__yaw=-58, rightArm__roll=4, rightArm__bend=-104, leftArm__pitch=-118,
    leftArm__yaw=58, leftArm__roll=-4, leftArm__bend=-98, torso__bend=22, torso__pitch=0, head__pitch=42, head__yaw=0,
    rightLeg__pitch=-20, rightLeg__bend=30, leftLeg__pitch=-24, leftLeg__bend=34, body__pitch=0, body__y=0.0)
key(353, rightArm__pitch=-120, rightArm__bend=-110, leftArm__pitch=-126, leftArm__bend=-104, head__pitch=36)
# #2120–2135: предплечья крест-накрест перед лицом
key(356, rightArm__pitch=-132, rightArm__yaw=-50, rightArm__bend=-100, leftArm__pitch=-138, leftArm__yaw=50,
    leftArm__bend=-96, torso__bend=14, head__pitch=18)
key(358, rightArm__pitch=-138, leftArm__pitch=-142, head__pitch=14)
# #2144–2151: скрещенные предплечья подняты над головой («крыша»), лицо открыто
key(359, ease="OUTQUAD", rightArm__pitch=-168, rightArm__yaw=-38, rightArm__roll=8, rightArm__bend=-92,
    leftArm__pitch=-170, leftArm__yaw=38, leftArm__roll=-8, leftArm__bend=-88, torso__bend=2, head__pitch=-4)
# #2152–2158: правая рука остаётся согнутой над головой, левая раскрывается в сторону
key(361, rightArm__pitch=-160, rightArm__yaw=-10, rightArm__roll=30, rightArm__bend=-70, leftArm__pitch=-40,
    leftArm__yaw=0, leftArm__roll=-80, leftArm__bend=-10, torso__bend=-6, head__pitch=-14)
# #2159–2186: откидывается далеко назад, руки широко в стороны, голова запрокинута
key(362, ease="OUTQUAD", body__pitch=58, rightArm__pitch=-20, rightArm__yaw=0, rightArm__roll=86, rightArm__bend=-8,
    leftArm__pitch=-20, leftArm__roll=-86, leftArm__bend=-8, torso__bend=-18, torso__pitch=0, head__pitch=-34,
    rightLeg__pitch=-10, rightLeg__roll=12, rightLeg__bend=12, leftLeg__pitch=4, leftLeg__roll=-12, leftLeg__bend=16)
key(366, body__pitch=62, rightArm__roll=88, leftArm__roll=-88, head__pitch=-38)
key(380, body__pitch=62)
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
        move = {"tick": int(round(tick)), "easing": "EASE" + ease, "turn": 0}
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
