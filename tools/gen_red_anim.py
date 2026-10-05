#!/usr/bin/env python3
"""Анимации тела для Красного и Максимального Красного (Player Animator / Emotecraft v3).

Результат (src/main/resources/assets/jujutsu_neon/player_animation/):
  red_cast.json — обычный Красный (18 тиков, бросок на 13-м — RedTechnique.R_FIRE).
                  Только руки, корпус и голова: ноги ванильные, можно идти.
  max_red.json  — Максимальный Красный (36 тиков, выстрел на 26-м — RedTechnique.M_FIRE), всё тело.

Покадрово по референсам (1 тик = 0,05 с):
  Красный: 0–2 рука вверх, 3–8 кисть у лба (над головой искра), 9–11 рука к груди (шарик в ладони),
           12 замах, 13 бросок вперёд, 14–18 возврат.
  Макс.:   всё время лицом к цели. 0–3 правая рука — печать у лба, левая поперёк груди;
           5 вспыхивает аура; 6–10 прогиб назад, руки раскрыты; 11–14 выпрямляется, ладони к груди;
           15–21 шар у правого плеча, левая рука целится; 22 ударная волна (присел); 23–25 замах;
           26 выстрел — правая рука прямо вперёд, луч из ладони; 27–31 отдача; 32–36 возврат.

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
# Лицом к цели всё время (без разворота корпуса): печать у лба → прогиб с аурой → руки к груди →
# шар у правого плеча, левая рука вытянута и целится → присел (ударная волна) → замах →
# ВЫСТРЕЛ прямой правой рукой вперёд, луч из ладони → отдача → стойка.
m = Anim()
m.key(0)
# 0–3: правая рука — печать у лба, левая поперёк груди; левая нога чуть вперёд
m.key(1, ease="OUTQUAD", rightArm__pitch=-70, rightArm__roll=30, rightArm__bend=-30, leftArm__pitch=-50,
      leftArm__roll=-24, leftArm__bend=-30, rightLeg__pitch=4, leftLeg__pitch=-4, body__y=-0.01)
m.key(3, ease="OUTQUAD", rightArm__pitch=-150, rightArm__yaw=-30, rightArm__roll=10, rightArm__bend=-96,
      leftArm__pitch=-88, leftArm__yaw=-52, leftArm__roll=-6, leftArm__bend=-64, head__pitch=-2,
      torso__bend=-2, rightLeg__pitch=8, rightLeg__bend=6, leftLeg__pitch=-8, leftLeg__bend=6, body__y=-0.015)
m.key(5, rightArm__pitch=-154, rightArm__bend=-98, leftArm__pitch=-92, torso__bend=-3, body__y=-0.02)
# 6–10: аура — прогиб назад, руки раскрываются, ноги упираются
m.key(7, ease="INOUTQUAD", body__pitch=10, torso__bend=-6, rightArm__pitch=-160, rightArm__yaw=-10,
      rightArm__roll=26, rightArm__bend=-60, leftArm__pitch=-150, leftArm__yaw=8, leftArm__roll=-26,
      leftArm__bend=-56, head__pitch=-10, rightLeg__pitch=12, rightLeg__bend=14, leftLeg__pitch=-14,
      leftLeg__bend=18, body__y=-0.04)
m.key(9, body__pitch=16, torso__bend=-9, rightArm__pitch=-164, rightArm__roll=34, rightArm__bend=-46,
      leftArm__pitch=-154, leftArm__roll=-34, leftArm__bend=-44, head__pitch=-14, rightLeg__pitch=16,
      rightLeg__bend=20, leftLeg__pitch=-18, leftLeg__bend=24, body__y=-0.06)
m.key(10, body__pitch=15, torso__bend=-8, head__pitch=-12)
# 11–14: выпрямляется, ленты сходятся — обе ладони перед грудью
m.key(12, ease="INOUTQUAD", body__pitch=5, torso__bend=0, rightArm__pitch=-78, rightArm__yaw=-34,
      rightArm__roll=4, rightArm__bend=-90, leftArm__pitch=-78, leftArm__yaw=34, leftArm__roll=-4,
      leftArm__bend=-90, head__pitch=-2, rightLeg__pitch=10, rightLeg__bend=12, leftLeg__pitch=-12,
      leftLeg__bend=14, body__y=-0.035)
m.key(14, body__pitch=2, torso__bend=2, rightArm__pitch=-74, rightArm__yaw=-36, rightArm__bend=-94,
      leftArm__pitch=-74, leftArm__yaw=36, leftArm__bend=-94, head__pitch=0, body__y=-0.03)
# 15–21: шар у правого плеча; левая рука вытянута вперёд — целится
m.key(16, rightArm__pitch=-42, rightArm__yaw=-6, rightArm__roll=18, rightArm__bend=-112, leftArm__pitch=-84,
      leftArm__yaw=12, leftArm__roll=-2, leftArm__bend=-10, torso__yaw=6, torso__bend=3)
m.key(18, rightArm__pitch=-44, rightArm__bend=-114, leftArm__pitch=-86, torso__bend=4, body__y=-0.035)
m.key(20, rightArm__pitch=-41, rightArm__bend=-112, leftArm__pitch=-85, torso__bend=3, body__y=-0.03)
# 22: ударная волна — слегка присел
m.key(22, ease="OUTQUAD", rightLeg__pitch=-6, rightLeg__bend=26, leftLeg__pitch=-18, leftLeg__bend=30,
      body__y=crouch_y(15), torso__bend=6)
# 23–25: замах — локоть назад, ладонь у плеча, корпус уводит правое плечо назад
m.key(25, ease="INQUAD", rightArm__pitch=-22, rightArm__yaw=-4, rightArm__roll=22, rightArm__bend=-120,
      torso__yaw=12, torso__bend=6, leftArm__pitch=-80, leftArm__yaw=14, leftArm__bend=-14)
# 26: ВЫСТРЕЛ — правая рука прямо вперёд, ладонью к цели; левая уходит назад
m.key(26, ease="OUTQUAD", rightArm__pitch=-90, rightArm__yaw=-6, rightArm__roll=2, rightArm__bend=0,
      torso__yaw=-8, torso__bend=3, leftArm__pitch=20, leftArm__yaw=0, leftArm__roll=-22, leftArm__bend=-18,
      body__pitch=2, rightLeg__pitch=10, rightLeg__bend=16, leftLeg__pitch=-18, leftLeg__bend=22,
      body__y=-0.04)
# 27–31: отдача — корпус чуть откидывается, рука подбрасывается вверх
m.key(28, ease="OUTQUAD", body__pitch=9, torso__bend=-3, torso__yaw=-4, rightArm__pitch=-112,
      rightArm__bend=-10, leftArm__pitch=14, leftArm__roll=-20, head__pitch=-4, rightLeg__pitch=14,
      rightLeg__bend=18, leftLeg__pitch=-20, leftLeg__bend=22, body__y=-0.05)
m.key(30, body__pitch=7, torso__bend=-2, rightArm__pitch=-104, rightArm__bend=-14, head__pitch=-3)
# 32–36: возвращается в стойку
m.key(33, ease="INOUTQUAD", body__pitch=2, torso__bend=1, torso__yaw=0, rightArm__pitch=-40, rightArm__roll=10,
      rightArm__bend=-30, leftArm__pitch=-6, leftArm__roll=-10, leftArm__bend=-14, head__pitch=0,
      rightLeg__pitch=4, rightLeg__bend=6, leftLeg__pitch=-4, leftLeg__bend=6, body__y=-0.01)
m.key(36, **neutral_changes())
(OUT / "max_red.json").write_text(json.dumps(m.build("max_red", 36, 8), indent=1))


# ---------------------------------------------------------------------------- ладонь (для клиента)
def hand_of(pose):
    """Примерно: где центр правой ладони в осях тела (вперёд, вправо, вверх от ног), модель 0,9375."""
    px = 1.0 / 16.0 * MODEL_SCALE
    p = lambda k: math.radians(pose.get("rightArm." + k, 0.0))

    def rx(v, a):
        x, y, z = v
        return (x, y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a))

    def ry(v, a):
        x, y, z = v
        return (x * math.cos(a) + z * math.sin(a), y, -x * math.sin(a) + z * math.cos(a))

    def rz(v, a):
        x, y, z = v
        return (x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a), z)

    # (вправо, вверх, вперёд); плечо: 5 px вправо, 22 px вверх; локоть 4 px ниже, ладонь ещё 5 px
    fore = rx((0.0, -5.0, 0.0), p("bend"))
    local = (fore[0], -4.0 + fore[1], fore[2])
    v = rz(ry(rx(local, p("pitch")), p("yaw")), p("roll"))
    v = ry(v, math.radians(pose.get("torso.yaw", 0.0)))
    r, u, f = 5.0 + v[0], 22.0 + v[1], v[2]
    r, u, f = r * px, u * px + pose.get("body.y", 0.0), f * px
    lean = math.radians(pose.get("body.pitch", 0.0))
    du = u - 0.7
    u2 = 0.7 + du * math.cos(lean) + f * math.sin(lean)
    f2 = f * math.cos(lean) - du * math.sin(lean)
    return f2, r, u2


pose = dict(NEUTRAL)
print("MAX_HAND:")
for tick, changes, _ in m.keys:
    pose.update(changes)
    f, rr, u = hand_of(pose)
    print("            {%d, %.2f, %.2f, %.2f}," % (tick, f, rr, u))

print("ok: red_cast.json, max_red.json")
