#!/usr/bin/env python3
"""Анимация тела для обычного Синего (Player Animator / Emotecraft v3).

Результат (src/main/resources/assets/jujutsu_neon/player_animation/):
  lapse_blue.json            — комбо по мобу/игроку (96 тиков, таймлайн LapseBlue.java)
  lapse_blue_grab_low.json   — захват блоков, когда игрок смотрит вниз (26 тиков, всё тело)
  lapse_blue_grab_mid.json   — ... прямо
  lapse_blue_grab_high.json  — ... вверх
  lapse_blue_hold.json       — пока блоки в руке: «дышит», рука управляет блоками (петля, только руки и корпус —
                               ноги и голова ванильные, можно ходить и смотреть)
  lapse_blue_throw.json      — бросок блоков (руки и корпус)

Персонаж «живой»: в каждой фазе двигаются обе руки, ноги переносят вес, корпус дышит,
голова следит; в замедлении руки балансируют.

Знаки (как в gen_max_purple_anim.py, проверено по коду Player Animator + bendy-lib):
  pitch < 0 — рука/нога вперёд-вверх; head pitch > 0 — взгляд вниз
  rightArm roll > 0 — правая рука наружу; leftArm roll < 0 — левая наружу (ноги так же)
  rightArm yaw < 0 — правая рука внутрь, к груди
  bend рук < 0 — сгиб в локте; bend ног > 0 — сгиб в колене (голень назад)
  torso bend > 0 — сгорбиться вперёд
  body: y в блоках (вверх +), pitch > 0 — откинуться назад,
        yaw > 0 — разворот всего тела влево (против часовой сверху)

Присед без «проваливания» ног: бедро -a, колено 2a → стопа под тазом, body.y = -0.75 * 0.9375 * (1 - cos a).

Поза касания (тики 58–70) зашита и в LapseBlue.java (KICK_LEG_PITCH, TUCK_*, CHAMBER_*, CROUCH_*):
стопа ставится ровно на верх модели цели, поэтому ноги с 58 по 70 и с 73 по 79 не двигаются.
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

# Позы, которые знает и LapseBlue.java
KICK_LEG_PITCH = -10
TUCK_LEG_PITCH = -70
TUCK_LEG_BEND = 100
CHAMBER_PITCH = -116
CHAMBER_BEND = 142
CROUCH_THIGH = -55
CROUCH_BEND = 110

MODEL_SCALE = 0.9375  # модель игрока рисуется в 0.9375, а смещение body — в блоках мира


def crouch_y(a_deg):
    return round(-0.75 * MODEL_SCALE * (1.0 - math.cos(math.radians(a_deg))), 3)


class Anim:
    def __init__(self, parts=None):
        self.keys = []
        self.yaw_keys = []
        self.parts = parts  # None — всё тело

    def key(self, tick, ease="INOUTSINE", **changes):
        self.keys.append((tick, {k.replace("__", "."): v for k, v in changes.items()}, ease))

    def yaw(self, tick, value, ease="INOUTSINE"):
        self.yaw_keys.append((tick, value, ease))

    def build(self, name, end_tick, stop_extra=8, loop=False):
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
        # Поворот всего тела — отдельным каналом (свои ключи и сглаживание, в т.ч. CONSTANT для «перескока» 360 → 0).
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
                "isLoop": loop,
                "returnTick": 0,
                "nsfw": False,
                "degrees": True,
                "moves": moves,
            },
        }


def neutral_changes():
    return {k.replace(".", "__"): v for k, v in NEUTRAL.items()}


# ============================================================================ комбо
c = Anim()
c.key(0)
# 0–4: правая рука выпрямляется к цели, вес на левую ногу вперёд, левая рука для баланса
c.key(2, ease="OUTQUAD", rightArm__pitch=-44, rightArm__yaw=-4, rightArm__roll=10, rightArm__bend=-30,
      torso__yaw=-4, head__pitch=3, leftArm__pitch=-6, leftArm__roll=-10, leftArm__bend=-14,
      rightLeg__bend=6, leftLeg__bend=8, body__y=-0.01)
c.key(4, ease="OUTQUAD", rightArm__pitch=-90, rightArm__yaw=-6, rightArm__roll=6, rightArm__bend=-4,
      torso__yaw=-10, torso__bend=4, head__pitch=2,
      leftArm__pitch=-12, leftArm__roll=-12, leftArm__bend=-22,
      rightLeg__pitch=8, rightLeg__bend=4, leftLeg__pitch=-10, leftLeg__bend=6)
# 4–9: держит, вокруг цели дуга-молния, в ладони синий шар — корпус «дышит», левая рука живая
c.key(6, rightArm__pitch=-91, torso__bend=5, leftArm__pitch=-16, leftArm__roll=-15, leftArm__bend=-26,
      head__yaw=-3, rightLeg__bend=8, leftLeg__bend=9, body__y=-0.02)
c.key(9, rightArm__pitch=-94, rightArm__bend=-2, torso__bend=6, leftArm__pitch=-10, leftArm__roll=-18,
      leftArm__bend=-20, head__yaw=0, body__y=-0.01)
# 12: синий взрыв на цели — отдача, корпус чуть назад, правая нога упирается
c.key(12, ease="OUTQUAD", rightArm__pitch=-82, rightArm__bend=-18, torso__bend=1, body__y=-0.02, body__pitch=3,
      leftArm__pitch=-4, leftArm__roll=-24, rightLeg__pitch=12, rightLeg__bend=8, leftLeg__pitch=-14,
      leftLeg__bend=10, head__pitch=0)
# 14–23: притягивает цель — рука сгибается, кулак к груди, вес назад, ноги упираются
c.key(15, rightArm__pitch=-82, rightArm__yaw=-10, rightArm__bend=-30, body__pitch=1)
c.key(19, ease="INOUTQUAD", rightArm__pitch=-62, rightArm__yaw=-26, rightArm__roll=10, rightArm__bend=-88,
      torso__yaw=6, body__y=-0.03, body__pitch=-3, leftArm__pitch=-40, leftArm__roll=-20, leftArm__bend=-50,
      rightLeg__pitch=14, rightLeg__bend=14, leftLeg__pitch=-16, leftLeg__bend=12, head__pitch=4)
c.key(23, rightArm__pitch=-38, rightArm__yaw=-30, rightArm__roll=12, rightArm__bend=-104,
      leftArm__pitch=-34, leftArm__yaw=10, leftArm__roll=-8, leftArm__bend=-74, torso__yaw=0, torso__bend=6,
      body__y=0.0, body__pitch=0, rightLeg__pitch=0, rightLeg__bend=6, leftLeg__pitch=0, leftLeg__bend=6)
# 24–31: разворот влево и присед (замах), руки раскрываются для баланса
c.key(25, torso__yaw=-8, head__yaw=6, rightArm__roll=24, leftArm__roll=-18)
c.key(27, rightLeg__pitch=-40, rightLeg__bend=80, leftLeg__pitch=-46, leftLeg__bend=92, body__y=crouch_y(43),
      torso__bend=18, torso__yaw=0, head__yaw=0, rightArm__pitch=18, rightArm__yaw=0, rightArm__roll=46,
      rightArm__bend=-20, leftArm__pitch=-56, leftArm__yaw=0, leftArm__roll=-34, leftArm__bend=-18, head__pitch=10)
c.key(29, rightArm__pitch=26, rightArm__roll=56, leftArm__pitch=-62, leftArm__roll=-40, torso__bend=22,
      rightLeg__pitch=-50, rightLeg__bend=100, leftLeg__pitch=-54, leftLeg__bend=108, body__y=crouch_y(52))
c.key(31, rightLeg__pitch=-58, rightLeg__bend=116, leftLeg__pitch=-62, leftLeg__bend=124, body__y=crouch_y(60),
      torso__bend=26)
# 31–35: низкая подсечка — правая нога вытянута в сторону, левая глубоко согнута, левая рука к земле
c.key(33, rightLeg__pitch=-12, rightLeg__roll=72, rightLeg__bend=4, leftLeg__pitch=-72, leftLeg__bend=140,
      body__y=crouch_y(70), torso__bend=30, leftArm__pitch=-28, leftArm__roll=-16, leftArm__bend=-8,
      rightArm__pitch=-20, rightArm__roll=70, rightArm__bend=-14, head__pitch=14)
c.key(35, ease="INQUAD", rightLeg__pitch=-70, rightLeg__roll=16, rightLeg__bend=24, leftLeg__pitch=-34,
      leftLeg__bend=66, body__y=crouch_y(34), torso__bend=8, rightArm__pitch=-90, rightArm__roll=40,
      leftArm__pitch=-90, leftArm__roll=-40, head__pitch=-10)
# 36: удар ногой вверх — цель улетает; руки вскинуты буквой V, на носках, корпус чуть назад
c.key(36, ease="OUTQUAD", rightLeg__pitch=-150, rightLeg__roll=6, rightLeg__bend=0, leftLeg__pitch=8,
      leftLeg__roll=0, leftLeg__bend=4, body__y=0.12, body__pitch=8, torso__bend=-10,
      rightArm__pitch=-160, rightArm__roll=34, rightArm__bend=-6, leftArm__pitch=-160, leftArm__roll=-34,
      leftArm__bend=-6, head__pitch=-30)
c.key(39, rightLeg__pitch=-96, rightLeg__bend=34, body__y=0.06, body__pitch=4, torso__bend=-4, head__pitch=-40,
      rightArm__pitch=-150, leftArm__pitch=-142)
c.key(41, rightLeg__pitch=-8, rightLeg__roll=0, rightLeg__bend=8, body__y=0.0, body__pitch=0, torso__bend=0,
      rightArm__pitch=-128, rightArm__roll=26, leftArm__pitch=-110, leftArm__roll=-30, head__pitch=-46)
# 43: присед перед прыжком к цели, руки назад
c.key(43, ease="INQUAD", rightLeg__pitch=-26, rightLeg__bend=52, leftLeg__pitch=-26, leftLeg__bend=52,
      body__y=crouch_y(26), rightArm__pitch=30, rightArm__roll=24, leftArm__pitch=30, leftArm__roll=-24,
      torso__bend=10, head__pitch=-46)
# 44: телепорт — в воздухе над целью, ноги поджаты, руки в стороны, смотрит вниз (дальше замедление)
c.key(44, rightLeg__pitch=-78, rightLeg__bend=108, leftLeg__pitch=-66, leftLeg__bend=112, body__y=0.0,
      torso__bend=12, rightArm__pitch=-40, rightArm__roll=72, rightArm__bend=-22, leftArm__pitch=-40,
      leftArm__roll=-72, leftArm__bend=-22, head__pitch=36)
# 44–55: медленно взводит правое колено, руки балансируют
c.key(47, rightArm__pitch=-30, rightArm__roll=62, rightArm__bend=-28, leftArm__pitch=-48, leftArm__roll=-82,
      leftArm__bend=-16, torso__bend=15, head__pitch=40, head__roll=-3)
c.key(50, rightLeg__pitch=-104, rightLeg__bend=134, leftLeg__pitch=TUCK_LEG_PITCH, leftLeg__bend=TUCK_LEG_BEND,
      torso__bend=18, rightArm__pitch=26, rightArm__roll=46, rightArm__bend=-30, leftArm__pitch=-62,
      leftArm__roll=-36, leftArm__bend=-28, head__pitch=46, head__roll=0)
c.key(53, torso__bend=20, rightArm__pitch=32, rightArm__roll=50, leftArm__pitch=-66, leftArm__roll=-42,
      head__pitch=48, rightLeg__pitch=-112, rightLeg__bend=139)
c.key(55, ease="INCUBIC", rightLeg__pitch=CHAMBER_PITCH, rightLeg__bend=CHAMBER_BEND, torso__bend=21,
      rightArm__pitch=34, leftArm__pitch=-70, head__pitch=50)
# 58: КАСАНИЕ — правая нога выпрямлена вниз (чуть вперёд), левая поджата. До 70 ноги неподвижны.
c.key(58, rightLeg__pitch=KICK_LEG_PITCH, rightLeg__roll=0, rightLeg__bend=0, leftLeg__pitch=TUCK_LEG_PITCH,
      leftLeg__roll=0, leftLeg__bend=TUCK_LEG_BEND, torso__bend=6, rightArm__pitch=-46, rightArm__roll=24,
      rightArm__bend=-18, leftArm__pitch=44, leftArm__roll=-34, leftArm__bend=-20, head__pitch=52)
# 58–70: летит вниз на цели — руки и корпус живые (ноги стоят)
c.key(61, torso__bend=9, rightArm__pitch=-38, rightArm__roll=34, leftArm__pitch=50, leftArm__roll=-40,
      head__pitch=50, head__yaw=-4)
c.key(64, torso__bend=10, rightArm__pitch=-30, rightArm__roll=40, leftArm__pitch=54, leftArm__roll=-46,
      head__pitch=48, head__yaw=0)
c.key(67, torso__bend=12, rightArm__pitch=-24, rightArm__roll=46, leftArm__pitch=46, leftArm__roll=-52,
      head__pitch=46, head__yaw=3)
c.key(70, ease="OUTQUAD", torso__bend=14, rightArm__pitch=-20, rightArm__roll=52, leftArm__pitch=40,
      leftArm__roll=-58, head__pitch=44, head__yaw=0)
# 70–80: удар о землю — присел на цели, тяжело дышит (ноги стоят)
c.key(73, ease="OUTQUAD", rightLeg__pitch=CROUCH_THIGH, rightLeg__bend=CROUCH_BEND, leftLeg__pitch=CROUCH_THIGH,
      leftLeg__bend=CROUCH_BEND, body__y=crouch_y(55), torso__bend=26, rightArm__pitch=-56, rightArm__roll=8,
      rightArm__bend=-30, leftArm__pitch=-20, leftArm__roll=-50, leftArm__bend=-24, head__pitch=34)
c.key(76, torso__bend=22, rightArm__pitch=-50, rightArm__bend=-34, leftArm__pitch=-16, leftArm__roll=-44,
      head__pitch=28, head__yaw=-6)
c.key(79, torso__bend=24, rightArm__pitch=-46, leftArm__roll=-48, head__pitch=26, head__yaw=0)
# 80–89: встаёт и отпрыгивает назад, руки в стороны
c.key(82, ease="OUTQUAD", rightLeg__pitch=12, rightLeg__bend=2, leftLeg__pitch=14, leftLeg__bend=2, body__y=0.0,
      body__pitch=6, torso__bend=-6, rightArm__pitch=-70, rightArm__roll=84, rightArm__bend=-10,
      leftArm__pitch=-70, leftArm__roll=-84, leftArm__bend=-10, head__pitch=10)
c.key(84, rightLeg__pitch=-20, rightLeg__bend=40, leftLeg__pitch=-10, leftLeg__bend=30, body__pitch=9,
      rightArm__pitch=-40, rightArm__roll=90, leftArm__pitch=-40, leftArm__roll=-90, head__pitch=6)
c.key(86, rightLeg__pitch=-38, rightLeg__bend=66, leftLeg__pitch=-30, leftLeg__bend=60, body__pitch=10,
      rightArm__pitch=-12, rightArm__roll=92, leftArm__pitch=-12, leftArm__roll=-92, head__pitch=4)
c.key(89, ease="OUTQUAD", rightLeg__pitch=-46, rightLeg__bend=92, leftLeg__pitch=-46, leftLeg__bend=92,
      body__y=crouch_y(46), body__pitch=0, torso__bend=14, rightArm__pitch=-6, rightArm__roll=86,
      leftArm__pitch=-6, leftArm__roll=-86, head__pitch=6)
c.key(91, rightArm__roll=74, leftArm__roll=-74, torso__bend=10, body__y=crouch_y(30), rightLeg__pitch=-30,
      rightLeg__bend=60, leftLeg__pitch=-30, leftLeg__bend=60)
c.key(93, rightLeg__pitch=-8, rightLeg__bend=12, leftLeg__pitch=-8, leftLeg__bend=12, body__y=crouch_y(8),
      torso__bend=4, rightArm__pitch=-4, rightArm__roll=40, rightArm__bend=-8, leftArm__pitch=-4,
      leftArm__roll=-40, leftArm__bend=-8, head__pitch=2)
c.key(96, **neutral_changes())

# Разворот влево на полный круг (24–36), в конце — незаметный «перескок» 360 → 0.
c.yaw(0, 0)
c.yaw(24, 0, "INSINE")
c.yaw(28, 100, "LINEAR")
c.yaw(31, 180, "LINEAR")
c.yaw(33, 270, "OUTQUAD")
c.yaw(36, 360, "CONSTANT")
c.yaw(37, 0)
c.yaw(96, 0)

(OUT / "lapse_blue.json").write_text(json.dumps(c.build("lapse_blue", 96), indent=1))


# ============================================================================ захват блоков
def grab(name, arm_pitch):
    g = Anim()
    g.key(0)
    # 0–4: рука к блокам ладонью вперёд, шаг левой вперёд, левая рука для баланса
    g.key(2, ease="OUTQUAD", rightArm__pitch=arm_pitch * 0.5, rightArm__yaw=-4, rightArm__roll=10,
          rightArm__bend=-30, torso__yaw=-4, leftArm__pitch=-6, leftArm__roll=-10, leftArm__bend=-14,
          rightLeg__bend=6, leftLeg__bend=8, body__y=-0.01)
    g.key(4, ease="OUTQUAD", rightArm__pitch=arm_pitch, rightArm__yaw=-6, rightArm__roll=6, rightArm__bend=-4,
          torso__yaw=-10, torso__bend=4, leftArm__pitch=-12, leftArm__roll=-12, leftArm__bend=-22,
          rightLeg__pitch=8, rightLeg__bend=4, leftLeg__pitch=-10, leftLeg__bend=6)
    g.key(7, rightArm__pitch=arm_pitch - 2, torso__bend=5, leftArm__pitch=-16, leftArm__roll=-16,
          leftArm__bend=-26, head__yaw=-3, rightLeg__bend=8, leftLeg__bend=9, body__y=-0.02)
    g.key(9, rightArm__pitch=arm_pitch - 4, rightArm__bend=-2, torso__bend=6, leftArm__pitch=-10,
          leftArm__roll=-18, head__yaw=0, body__y=-0.01)
    # 12: синий вспыхивает на блоках — отдача
    g.key(12, ease="OUTQUAD", rightArm__pitch=arm_pitch + 6, rightArm__bend=-16, torso__bend=2, body__y=-0.02,
          body__pitch=3, leftArm__pitch=-4, leftArm__roll=-24, rightLeg__pitch=12, leftLeg__pitch=-14)
    # 12–20: притягивает блоки — ладонь к себе, вес назад
    g.key(15, rightArm__pitch=arm_pitch + 4, rightArm__yaw=-12, rightArm__bend=-34, body__pitch=4,
          rightLeg__pitch=14, rightLeg__bend=12, leftLeg__pitch=-14, leftLeg__bend=10)
    g.key(20, ease="INOUTQUAD", rightArm__pitch=-64, rightArm__yaw=-24, rightArm__roll=12, rightArm__bend=-84,
          torso__yaw=2, body__y=0.0, body__pitch=0, torso__bend=4, leftArm__pitch=-30, leftArm__roll=-16,
          leftArm__bend=-40)
    # 20–26: рука перед собой — управляет блоками; ноги в обычную стойку
    g.key(26, rightArm__pitch=-40, rightArm__yaw=-8, rightArm__roll=10, rightArm__bend=-40, torso__bend=2,
          torso__yaw=0, leftArm__pitch=-6, leftArm__roll=-10, leftArm__bend=-14, rightLeg__pitch=0,
          rightLeg__bend=2, leftLeg__pitch=0, leftLeg__bend=2)
    (OUT / f"{name}.json").write_text(json.dumps(g.build(name, 26, 4), indent=1))


grab("lapse_blue_grab_low", -48)
grab("lapse_blue_grab_mid", -88)
grab("lapse_blue_grab_high", -128)

# Удержание блоков: петля «дыхания», только руки и корпус (ходить и смотреть можно).
hold = Anim(parts={"rightArm", "leftArm", "torso"})
hold.key(0, rightArm__pitch=-40, rightArm__yaw=-8, rightArm__roll=10, rightArm__bend=-40, leftArm__pitch=-6,
         leftArm__roll=-10, leftArm__bend=-14, torso__bend=2)
hold.key(10, rightArm__pitch=-45, rightArm__roll=13, rightArm__bend=-45, leftArm__pitch=-11, leftArm__roll=-13,
         leftArm__bend=-18, torso__bend=3.5)
hold.key(20, rightArm__pitch=-37, rightArm__roll=8, rightArm__bend=-37, leftArm__pitch=-4, leftArm__roll=-8,
         leftArm__bend=-12, torso__bend=1.5)
hold.key(30, rightArm__pitch=-40, rightArm__roll=10, rightArm__bend=-40, leftArm__pitch=-6, leftArm__roll=-10,
         leftArm__bend=-14, torso__bend=2)
(OUT / "lapse_blue_hold.json").write_text(json.dumps(hold.build("lapse_blue_hold", 30, 4, loop=True), indent=1))

# Бросок: рука выбрасывается вперёд, корпус доворачивается, левая рука назад.
throw = Anim(parts={"rightArm", "leftArm", "torso", "head"})
throw.key(0, rightArm__pitch=-40, rightArm__yaw=-8, rightArm__roll=10, rightArm__bend=-40, leftArm__pitch=-6,
          leftArm__roll=-10, leftArm__bend=-14, torso__bend=2)
throw.key(2, ease="INQUAD", rightArm__pitch=-20, rightArm__yaw=-30, rightArm__bend=-90, torso__yaw=8,
          leftArm__pitch=-20, leftArm__roll=-20)
throw.key(5, ease="OUTQUAD", rightArm__pitch=-96, rightArm__yaw=-4, rightArm__roll=4, rightArm__bend=-2,
          torso__yaw=-14, torso__bend=6, leftArm__pitch=28, leftArm__roll=-26, leftArm__bend=-20, head__pitch=2)
throw.key(9, rightArm__pitch=-80, rightArm__bend=-10, torso__yaw=-8, torso__bend=4, leftArm__pitch=14)
throw.key(14, **neutral_changes())
(OUT / "lapse_blue_throw.json").write_text(json.dumps(throw.build("lapse_blue_throw", 14, 6), indent=1))

print("ok:", sorted(p.name for p in OUT.glob("lapse_blue*.json")))
