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


# ---- 0–12 (0–0,6 с): разворот, правая рука выходит вперёд-вправо ладонью вверх — там появится Синий
key(0)
key(5, rightArm__pitch=-38, rightArm__yaw=-4, rightArm__roll=16, rightArm__bend=-18, torso__yaw=-6,
    head__pitch=6, leftArm__pitch=-8, leftArm__roll=-8, leftArm__bend=-12)
key(12, rightArm__pitch=-62, rightArm__yaw=2, rightArm__roll=30, rightArm__bend=-30, torso__yaw=-10,
    torso__bend=6, head__pitch=20, head__yaw=-10, leftArm__pitch=-14, leftArm__roll=-12, leftArm__bend=-18,
    rightLeg__bend=6, leftLeg__bend=4)
# ---- 14–33 (0,7–1,65 с): Синий в ладони, персонаж смотрит на него («дышит»)
key(18, head__pitch=26, head__yaw=-12, torso__bend=10, rightArm__bend=-34)
key(25, head__pitch=22, head__yaw=-10, torso__bend=8, rightArm__pitch=-66, rightArm__bend=-28)
# ---- 28–41 (1,4–2,05 с): замах как у питчера — корпус разворачивается вбок (правое плечо назад),
#      локоть на уровне плеча в сторону, предплечье вверх, Синий за плечом; вес на задней ноге
key(28, rightArm__pitch=-50, rightArm__yaw=60, rightArm__roll=30, rightArm__bend=-80, torso__yaw=15,
    torso__bend=4, head__pitch=8, head__yaw=0, rightLeg__bend=10, leftLeg__bend=6)
key(33, rightArm__pitch=20, rightArm__yaw=90, rightArm__roll=100, rightArm__bend=-90, torso__yaw=40,
    torso__bend=-4, torso__pitch=0, head__pitch=2, leftArm__pitch=-70, leftArm__roll=-14, leftArm__bend=-20,
    rightLeg__pitch=8, rightLeg__bend=24, leftLeg__pitch=-26, leftLeg__bend=16, body__y=-0.05)
key(38, ease="INQUAD", rightArm__pitch=26, rightArm__roll=104, torso__yaw=46, torso__bend=-6, leftArm__pitch=-78,
    rightLeg__bend=28, leftLeg__pitch=-30)
# ---- 41: бросок — корпус резко доворачивается, рука хлёстом вперёд-вверх и отпускает Синего
key(41, ease="OUTQUAD", rightArm__pitch=-130, rightArm__yaw=0, rightArm__roll=-20, rightArm__bend=-10,
    torso__yaw=-25, torso__bend=16, torso__pitch=0, head__pitch=-6, leftArm__pitch=-20, leftArm__roll=-24,
    leftArm__bend=-50, rightLeg__pitch=26, rightLeg__bend=20, leftLeg__pitch=-20, leftLeg__bend=12, body__y=-0.02)
# ---- 44–53: провожает взглядом — Синий по спирали поднимается вокруг, голова вверх
key(44, rightArm__pitch=-160, rightArm__yaw=-10, rightArm__roll=0, rightArm__bend=-12, torso__yaw=-28,
    torso__bend=6, head__pitch=-30)
key(48, rightArm__pitch=-120, rightArm__bend=-20, torso__yaw=-12, torso__bend=0, head__pitch=-46,
    leftArm__pitch=-12, leftArm__bend=-20, rightLeg__pitch=6, rightLeg__bend=8, leftLeg__pitch=-4, leftLeg__bend=6,
    body__y=0.0)
key(53, rightArm__pitch=-40, rightArm__yaw=-4, rightArm__roll=10, rightArm__bend=-22, torso__yaw=-2,
    torso__bend=0, torso__pitch=0, head__pitch=-24, leftArm__pitch=-10, leftArm__roll=-10, leftArm__bend=-16,
    rightLeg__pitch=0, rightLeg__bend=6, leftLeg__pitch=0, leftLeg__bend=4)
# ---- 56–68 (2,8–3,4 с): рука плавно поднимается и сгибается — кулак на уровне головы рядом с лицом;
#      в нём загорается Красный. Голова смотрит прямо.
key(61, rightArm__pitch=-92, rightArm__yaw=-12, rightArm__roll=16, rightArm__bend=-96, head__pitch=0,
    head__yaw=0, torso__bend=3, torso__yaw=-4)
key(65, rightArm__pitch=-94, rightArm__bend=-100, head__pitch=2, torso__bend=4)
key(69, ease="OUTQUAD", rightArm__pitch=-93, rightArm__bend=-99, head__pitch=1, torso__bend=3)
# ---- 69–75: Красный вылетает из поднятой руки вверх — рука на месте, только короткая отдача
key(71, rightArm__pitch=-102, rightArm__bend=-86, torso__bend=-5, torso__pitch=-2, head__pitch=-4, body__y=0.03)
key(76, rightArm__pitch=-93, rightArm__bend=-98, torso__bend=2, torso__pitch=0, head__pitch=0, body__y=0.0)
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
# ---- 350–358 (17,5–17,9 с): крупно спереди — руки скрещены перед лицом, фиолетовый за спиной
key(348, rightArm__pitch=-118, rightArm__yaw=-58, rightArm__roll=4, rightArm__bend=-108, leftArm__pitch=-124,
    leftArm__yaw=58, leftArm__roll=-4, leftArm__bend=-100, torso__bend=22, head__pitch=14,
    rightLeg__pitch=-44, rightLeg__bend=74, leftLeg__pitch=-48, leftLeg__bend=78)
key(353, rightArm__bend=-112, leftArm__bend=-104, torso__bend=24, head__pitch=16)
key(358, ease="OUTQUAD", rightArm__pitch=-122, rightArm__bend=-114, leftArm__pitch=-128, leftArm__bend=-108,
    torso__bend=26, head__pitch=18, rightLeg__bend=80, leftLeg__bend=84)
# ---- 359–364 (17,95–18,2 с): выпрямляется — руки широко в стороны, грудь вперёд, голова назад, ноги врозь
key(361, rightArm__pitch=-18, rightArm__yaw=0, rightArm__roll=78, rightArm__bend=-8, leftArm__pitch=-18,
    leftArm__yaw=0, leftArm__roll=-78, leftArm__bend=-8, torso__bend=-20, torso__pitch=-6, torso__yaw=0,
    head__pitch=-38, head__yaw=0, rightLeg__pitch=-6, rightLeg__roll=20, rightLeg__bend=10,
    leftLeg__pitch=6, leftLeg__roll=-20, leftLeg__bend=12)
key(364, rightArm__roll=84, leftArm__roll=-84, torso__bend=-23, head__pitch=-42, rightLeg__roll=22, leftLeg__roll=-22)
key(380, rightArm__roll=82, leftArm__roll=-82, torso__bend=-21, head__pitch=-40)
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
