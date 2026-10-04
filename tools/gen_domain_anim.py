#!/usr/bin/env python3
"""Анимация тела для каста Расширения территории (Player Animator / Emotecraft v3).

Результат: src/main/resources/assets/jujutsu_neon/player_animation/domain_cast.json
Тики совпадают с таймлайном DomainExpansion (катсцена 216 тиков = 10,8 с).

Знаки те же, что в gen_max_purple_anim.py:
  pitch < 0 — рука вперёд-вверх; head pitch > 0 — взгляд вниз
  rightArm yaw < 0 — правая рука внутрь, к лицу; roll > 0 — наружу
  bend рук < 0 — сгиб в локте (предплечье вперёд/вверх)
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/player_animation/domain_cast.json"
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
NEUTRAL.update({"rightArm.bend": -6, "leftArm.bend": -6, "rightArm.roll": 4, "leftArm.roll": -4,
                "rightLeg.roll": 3, "leftLeg.roll": -3})
KEYS = []


def key(tick, ease="INOUTSINE", **changes):
    KEYS.append((tick, {k.replace("__", "."): v for k, v in changes.items()}, ease))


# 0–10 (0–0,5 с): стоит спокойно
key(0)
key(8, rightArm__pitch=-8, rightArm__bend=-14, leftArm__pitch=-4, leftArm__roll=-7, leftArm__bend=-10)
# 10–60 (0,5–3,0 с): рука плавно поднимается к лицу, локоть сгибается — камера ведёт кончик руки
key(22, rightArm__pitch=-40, rightArm__yaw=-10, rightArm__roll=12, rightArm__bend=-58, head__pitch=4)
key(36, rightArm__pitch=-70, rightArm__yaw=-24, rightArm__roll=12, rightArm__bend=-94, head__pitch=6,
    torso__bend=2)
key(50, rightArm__pitch=-92, rightArm__yaw=-34, rightArm__roll=10, rightArm__bend=-116, head__pitch=6)
# 60–106 (3,0–5,3 с): жест расширения у лица
key(60, ease="OUTQUAD", rightArm__pitch=-100, rightArm__yaw=-40, rightArm__roll=8, rightArm__bend=-126,
    head__pitch=7, torso__bend=3)
key(72, head__pitch=5, head__yaw=3, rightArm__bend=-124)
key(84, head__pitch=8, head__yaw=0, rightArm__bend=-128)
# 92 (4,6 с): рука закрывает лицо
key(92, rightArm__pitch=-104, rightArm__yaw=-47, rightArm__bend=-133, head__pitch=11, torso__bend=5)
# 100 (5,0 с): рука чуть уходит вперёд-вниз, жест перед собой
key(100, rightArm__pitch=-88, rightArm__yaw=-36, rightArm__bend=-112, head__pitch=2, torso__bend=2)
key(130, rightArm__pitch=-90, rightArm__bend=-114, head__pitch=1)
key(160, rightArm__pitch=-89, rightArm__bend=-113, head__pitch=2)
# 176–196 (8,8–9,8 с): крупно глаза — голова чуть приподнята
key(176, head__pitch=-3, rightArm__pitch=-91, rightArm__bend=-115)
key(196, head__pitch=-4)
# 198–216 (9,9–10,8 с): рука опускается, территория раскрыта
key(206, rightArm__pitch=-50, rightArm__yaw=-16, rightArm__roll=8, rightArm__bend=-58, head__pitch=0,
    torso__bend=0)
key(216, rightArm__pitch=-8, rightArm__yaw=0, rightArm__roll=4, rightArm__bend=-8, leftArm__pitch=0,
    leftArm__roll=-4, leftArm__bend=-6)

END_TICK = 216


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
        "name": "domain_cast",
        "author": "Jujutsu Neon",
        "description": "Domain Expansion cast body animation",
        "emote": {
            "beginTick": 0,
            "endTick": END_TICK,
            "stopTick": END_TICK + 8,
            "isLoop": False,
            "returnTick": 0,
            "nsfw": False,
            "degrees": True,
            "moves": moves,
        },
    }


OUT.write_text(json.dumps(build(), indent=1))
print(OUT, OUT.stat().st_size, "bytes,", len(KEYS), "keyframes")
