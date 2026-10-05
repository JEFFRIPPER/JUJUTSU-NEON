#!/usr/bin/env python3
"""Анимации тела для Красного и Максимального Красного (Player Animator / Emotecraft v3).

Результат (src/main/resources/assets/jujutsu_neon/player_animation/):
  red_cast.json — обычный Красный (18 тиков, бросок на 13-м — RedTechnique.R_FIRE).
                  Только руки, корпус и голова: ноги ванильные, можно идти.
  max_red.json  — Максимальный Красный (36 тиков, выстрел на 26-м — RedTechnique.M_FIRE), всё тело.

Покадрово по референсам (1 тик = 0,05 с):
  Красный: 0–2 рука вверх, 3–8 кисть у лба (над головой искра), 9–11 рука к груди (шарик в ладони),
           12 замах, 13 бросок вперёд, 14–18 возврат.
  Макс.:   0–3 разворот боком (правое плечо к цели), правая рука — печать у лба, левая поперёк груди;
           5 вспыхивает аура; 6–10 сильный прогиб назад со скруткой; 11–14 выпрямляется, руки к груди;
           15–21 держит шар у плеча; 22 ударная волна (присел); 23–25 замах; 26 выстрел — рука вбок к цели;
           27–31 отдача: откинулся, рука над головой; 32–36 возврат.

Плавность: каждое движение — ключ с OUTQUAD/INOUTSINE и промежуточные «живые» ключи (дыхание, вес,
доводки), никаких резких перескоков; в конце stopTick даёт мягкий выход в обычную позу.

Знаки — как в gen_lapse_blue_anim.py:
  pitch < 0 — рука/нога вперёд-вверх; rightArm roll > 0 — правая рука наружу; leftArm roll < 0 — левая наружу;
  rightArm yaw < 0 — правая рука внутрь; bend рук < 0 — сгиб в локте; bend ног > 0 — колено;
  body: y (вверх +), pitch > 0 — откинуться назад, yaw > 0 — разворот влево.
"""
import json
import math
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/player_animation"
OUT.mkdir(parents=True, exist_ok=True)

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
MODEL_SCALE = 0.9375


def crouch_y(a_deg):
    return round(-0.75 * MODEL_SCALE * (1.0 - math.cos(math.radians(a_deg))), 3)


class Anim:
    def __init__(self, parts=None):
        self.keys = []
        self.yaw_keys = []
        self.parts = parts

    def key(self, tick, ease="INOUTSINE", **changes):
        self.keys.append((tick, {k.replace("__", "."): v for k, v in changes.items()}, ease))

    def yaw(self, tick, value, ease="INOUTSINE"):
        self.yaw_keys.append((tick, value, ease))

    def build(self, name, end_tick, stop_extra=6):
        pose = dict(NEUTRAL)
        moves = []
        for tick, changes, ease in self.keys:
            pose.update(changes)
            by_part = {}
            for chan, value in pose.items():
                part, ch = chan.split(".")
                if self.parts is not None and part not in self.parts:
                    continue
                by_part.setdefault(part, {})[ch] = round(float(value), 3)
            move = {"tick": tick, "easing": "EASE" + ease, "turn": 0}
            move.update(by_part)
            moves.append(move)
        for tick, value, ease in self.yaw_keys:
            moves.append({"tick": tick, "easing": "EASE" + ease, "turn": 0, "body": {"yaw": round(float(value), 3)}})
        moves.sort(key=lambda m: m["tick"])
        return {
            "version": 3,
            "name": name,
            "author": "Jujutsu Neon",
            "description": name,
            "emote": {
                "beginTick": 0,
                "endTick": end_tick,
                "stopTick": end_tick + stop_extra,
                "isLoop": False,
                "returnTick": 0,
                "nsfw": False,
                "degrees": True,
                "moves": moves,
            },
        }


def neutral_changes():
    return {k.replace(".", "__"): v for k, v in NEUTRAL.items()}


# ============================================================================ обычный Красный
r = Anim(parts={"rightArm", "leftArm", "torso", "head"})
r.key(0)
# 0–2: правая рука резко вверх-наружу, корпус чуть уводит правое плечо назад
r.key(1, ease="OUTQUAD", rightArm__pitch=-70, rightArm__roll=26, rightArm__bend=-24, torso__yaw=-3,
      leftArm__pitch=-4, leftArm__roll=-8)
r.key(2, ease="OUTQUAD", rightArm__pitch=-128, rightArm__yaw=-8, rightArm__roll=26, rightArm__bend=-46,
      torso__yaw=-6, torso__bend=-2, head__pitch=-3, leftArm__pitch=-10, leftArm__roll=-12, leftArm__bend=-16)
# 3–8: кисть у лба, ладонь вверх — над головой загорается и растёт искра; дыхание
r.key(4, ease="OUTQUAD", rightArm__pitch=-164, rightArm__yaw=-22, rightArm__roll=12, rightArm__bend=-72,
      torso__yaw=-4, torso__bend=-4, head__pitch=-5, leftArm__pitch=-14, leftArm__roll=-14, leftArm__bend=-20)
r.key(6, rightArm__pitch=-168, rightArm__yaw=-24, rightArm__bend=-76, torso__bend=-5, head__pitch=-6,
      leftArm__pitch=-16, leftArm__roll=-16, leftArm__bend=-22)
r.key(8, rightArm__pitch=-162, rightArm__yaw=-26, rightArm__bend=-82, torso__bend=-3, head__pitch=-3,
      leftArm__pitch=-12)
# 9–11: рука опускается к груди — шарик в ладони у груди
r.key(10, ease="INOUTQUAD", rightArm__pitch=-80, rightArm__yaw=-40, rightArm__roll=4, rightArm__bend=-102,
      torso__yaw=6, torso__bend=3, head__pitch=2, leftArm__pitch=-20, leftArm__roll=-16, leftArm__bend=-30)
r.key(11, rightArm__pitch=-72, rightArm__yaw=-30, rightArm__roll=8, rightArm__bend=-96, torso__yaw=10,
      torso__bend=4)
# 12: замах — рука уходит вбок к бедру, корпус скручивается вправо
r.key(12, ease="INQUAD", rightArm__pitch=-46, rightArm__yaw=2, rightArm__roll=20, rightArm__bend=-64,
      torso__yaw=14, torso__bend=5, leftArm__pitch=-26, leftArm__roll=-18)
# 13: бросок — рука выпрямляется вперёд, корпус доворачивается, левая рука назад
r.key(13, ease="OUTQUAD", rightArm__pitch=-90, rightArm__yaw=4, rightArm__roll=8, rightArm__bend=-4,
      torso__yaw=-14, torso__bend=7, head__pitch=2, leftArm__pitch=22, leftArm__roll=-22, leftArm__bend=-18)
r.key(15, rightArm__pitch=-84, rightArm__bend=-12, torso__yaw=-10, torso__bend=5, leftArm__pitch=14)
r.key(18, **neutral_changes())
(OUT / "red_cast.json").write_text(json.dumps(r.build("red_cast", 18, 5), indent=1))


# ============================================================================ Максимальный Красный
m = Anim()
m.key(0)
# 0–3: разворот боком, руки поднимаются; правая — печать у лба, левая поперёк груди
m.key(1, ease="OUTQUAD", rightArm__pitch=-70, rightArm__roll=46, rightArm__bend=-30, leftArm__pitch=-64,
      leftArm__roll=-44, leftArm__bend=-30, head__yaw=-20, rightLeg__bend=6, leftLeg__bend=6, body__y=-0.01)
m.key(3, ease="OUTQUAD", rightArm__pitch=-150, rightArm__yaw=-30, rightArm__roll=10, rightArm__bend=-96,
      leftArm__pitch=-92, leftArm__yaw=-58, leftArm__roll=-6, leftArm__bend=-64, head__yaw=-66, head__pitch=-2,
      torso__bend=-2, rightLeg__pitch=8, rightLeg__roll=4, rightLeg__bend=8, leftLeg__pitch=-8, leftLeg__roll=-4,
      leftLeg__bend=8, body__y=-0.015)
m.key(5, rightArm__pitch=-154, rightArm__bend=-98, leftArm__pitch=-95, torso__bend=-4, head__yaw=-68,
      body__y=-0.02)
# 6–10: аура — сильный прогиб назад со скруткой, ноги упираются
m.key(7, ease="INOUTQUAD", body__pitch=14, torso__bend=-9, torso__yaw=8, rightArm__pitch=-166, rightArm__roll=18,
      rightArm__bend=-70, leftArm__pitch=-112, leftArm__roll=-20, leftArm__bend=-48, head__pitch=-10, head__yaw=-60,
      rightLeg__pitch=16, rightLeg__bend=18, leftLeg__pitch=-20, leftLeg__bend=24, body__y=-0.05)
m.key(9, body__pitch=24, torso__bend=-13, torso__yaw=14, rightArm__pitch=-172, rightArm__roll=24,
      rightArm__bend=-58, leftArm__pitch=-124, leftArm__roll=-28, head__pitch=-16, head__yaw=-54,
      rightLeg__pitch=22, rightLeg__bend=28, leftLeg__pitch=-28, leftLeg__bend=34, body__y=-0.08)
m.key(10, body__pitch=22, torso__bend=-12, torso__yaw=12, head__pitch=-13)
# 11–14: выпрямляется, ленты сходятся — обе руки к груди
m.key(12, ease="INOUTQUAD", body__pitch=9, torso__bend=-2, torso__yaw=4, rightArm__pitch=-100, rightArm__yaw=-38,
      rightArm__roll=6, rightArm__bend=-96, leftArm__pitch=-84, leftArm__yaw=-46, leftArm__roll=-6,
      leftArm__bend=-98, head__pitch=-2, head__yaw=-64, rightLeg__pitch=14, rightLeg__bend=16,
      leftLeg__pitch=-16, leftLeg__bend=18, body__y=-0.04)
m.key(14, body__pitch=3, torso__bend=3, torso__yaw=0, rightArm__pitch=-90, rightArm__yaw=-34,
      rightArm__bend=-92, leftArm__pitch=-80, leftArm__yaw=-44, leftArm__bend=-100, head__pitch=0, head__yaw=-68,
      rightLeg__pitch=10, rightLeg__bend=12, leftLeg__pitch=-12, leftLeg__bend=14, body__y=-0.03)
# 15–21: шар у правого плеча, целится в сторону цели; дышит
m.key(16, rightArm__pitch=-64, rightArm__yaw=-6, rightArm__roll=44, rightArm__bend=-96, leftArm__pitch=-70,
      leftArm__yaw=-40, leftArm__bend=-92, torso__yaw=-6, torso__bend=4, head__yaw=-74)
m.key(18, rightArm__pitch=-66, rightArm__roll=46, rightArm__bend=-98, torso__bend=5, body__y=-0.035)
m.key(20, rightArm__pitch=-62, rightArm__roll=44, rightArm__bend=-94, torso__bend=4, body__y=-0.03)
# 22: ударная волна — чуть присел
m.key(22, ease="OUTQUAD", rightLeg__pitch=-16, rightLeg__bend=34, leftLeg__pitch=-20, leftLeg__bend=38,
      body__y=crouch_y(18), torso__bend=8)
# 23–25: замах — рука к груди, корпус скручивается от цели
m.key(25, ease="INQUAD", rightArm__pitch=-74, rightArm__yaw=-30, rightArm__roll=20, rightArm__bend=-110,
      torso__yaw=10, torso__bend=6, leftArm__pitch=-56, leftArm__roll=-24)
# 26: ВЫСТРЕЛ — правая рука выпрямляется вбок, к цели; отдача начинается
m.key(26, ease="OUTQUAD", rightArm__pitch=-6, rightArm__yaw=0, rightArm__roll=92, rightArm__bend=0,
      torso__yaw=-10, torso__bend=2, leftArm__pitch=-34, leftArm__yaw=0, leftArm__roll=-64, leftArm__bend=-36,
      head__yaw=-76, body__pitch=8, rightLeg__pitch=16, rightLeg__bend=18, leftLeg__pitch=-22, leftLeg__bend=24,
      body__y=-0.04)
# 27–31: отдача — откинулся назад, правая рука вскинута над головой
m.key(28, ease="OUTQUAD", body__pitch=18, torso__bend=-6, rightArm__pitch=-140, rightArm__roll=44,
      rightArm__bend=-78, leftArm__pitch=-64, leftArm__roll=-42, leftArm__bend=-30, head__pitch=-8, head__yaw=-58,
      rightLeg__pitch=22, rightLeg__bend=22, leftLeg__pitch=-26, leftLeg__bend=26, body__y=-0.06)
m.key(30, body__pitch=16, torso__bend=-4, rightArm__pitch=-146, rightArm__bend=-84, head__pitch=-6)
# 32–36: возвращается в стойку
m.key(33, ease="INOUTQUAD", body__pitch=5, torso__bend=2, rightArm__pitch=-56, rightArm__roll=26,
      rightArm__bend=-40, leftArm__pitch=-18, leftArm__roll=-18, leftArm__bend=-20, head__pitch=0, head__yaw=-24,
      rightLeg__pitch=6, rightLeg__bend=8, leftLeg__pitch=-6, leftLeg__bend=8, body__y=-0.015)
m.key(36, **neutral_changes())

# Поворот всего тела: боком к цели (правое плечо вперёд), к концу — обратно.
m.yaw(0, 0)
m.yaw(1, 30, "OUTQUAD")
m.yaw(3, 80, "INOUTSINE")
m.yaw(26, 80, "OUTQUAD")
m.yaw(29, 72, "INOUTSINE")
m.yaw(33, 28, "INOUTSINE")
m.yaw(36, 0)
(OUT / "max_red.json").write_text(json.dumps(m.build("max_red", 36, 8), indent=1))

print("ok: red_cast.json, max_red.json")
