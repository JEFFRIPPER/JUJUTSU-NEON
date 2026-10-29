"""Анимации техник Годжо для модели R15, нарисованные позами (как в Blender/Moon Animator для Roblox).

  python3 tools/rig/gen_techniques.py        — пишет animations/player/gojo.animation.json и проверяет позы
  python3 tools/rig/keys.py src/main/resources/assets/jujutsu_neon/animations/player/gojo.animation.json \
      animation.player.red_cast 0.15,0.65     — крупные кадры ключевых поз
  python3 tools/rig/preview.py anim <тот же файл> animation.player.red_cast --views back,side,front — гифка

Принципы (гайд Roblox-аниматоров, конспект пользователя):
  1. Антиципация: перед действием замах в обратную сторону и присед.
  2. Overlapping action: таз начинает, корпус догоняет, плечо, предплечье, кисть приходят последними (LAG).
  3. Дуги: на пути кисти — промежуточный ключ (рука идёт по дуге, а не по прямой).
  4. Микро-стоп: в момент выстрела/удара поза держится 2–3 кадра (hold), потом отдача.
  5. Крайние позы всем телом: стойка ног, присед (LowerTorso вниз), скрут корпуса, наклон.
  6. Руки всё время перед глазами (вид от первого лица — руки модели) и не проходят сквозь голову (check()).

Оси Blockbench для R15 (проверено tools/rig/where.py):
  рука/бедро X < 0 — вперёд/вверх; предплечье X < 0 — сгиб локтя; голень X > 0 — сгиб колена;
  правая рука: Z > 0 — наружу, Y < 0 — кисть к центру груди (левая рука — знаки Y и Z наоборот);
  корпус X > 0 — наклон вперёд, Y > 0 — скрут вправо, Z > 0 — наклон влево; голова X > 0 — взгляд вниз;
  LowerTorso.pos: X > 0 — влево, Y > 0 — вверх, Z > 0 — назад (пиксели).

Клипы отсюда convert_pa.py больше не создаёт (MADE_HERE).
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anim_dsl import Anim, write  # noqa: E402

ZERO = (0.0, 0.0, 0.0)

# Насколько раньше (−) или позже (+) ключа приходит кость: корпус ведёт, кисть догоняет.
LAG = {
    'LowerTorso': -0.05, 'LowerTorso.pos': -0.05,
    'UpperTorso': -0.03,
    'Head': 0.02,
    'RightUpperArm': 0.0, 'LeftUpperArm': 0.0,
    'RightLowerArm': 0.02, 'LeftLowerArm': 0.02,
    'RightHand': 0.04, 'LeftHand': 0.04,
}

REST = {
    'LowerTorso.pos': ZERO, 'LowerTorso': ZERO, 'UpperTorso': ZERO, 'Head': ZERO,
    'RightUpperArm': (0, 0, 4), 'RightLowerArm': (-6, 0, 0), 'RightHand': ZERO,
    'LeftUpperArm': (0, 0, -4), 'LeftLowerArm': (-6, 0, 0), 'LeftHand': ZERO,
    'RightUpperLeg': ZERO, 'RightLowerLeg': ZERO, 'RightFoot': ZERO,
    'LeftUpperLeg': ZERO, 'LeftLowerLeg': ZERO, 'LeftFoot': ZERO,
}

# Конец техники — мягкая стойка (дальше fade_out в обычную анимацию).
SETTLE = {
    'LowerTorso.pos': (0, -0.4, 0), 'LowerTorso': ZERO, 'UpperTorso': (2, 0, 0), 'Head': ZERO,
    'RightUpperArm': (-10, -4, 8), 'RightLowerArm': (-18, 0, 0), 'RightHand': ZERO,
    'LeftUpperArm': (-6, 4, -8), 'LeftLowerArm': (-14, 0, 0), 'LeftHand': ZERO,
    'RightUpperLeg': (-4, 0, 3), 'RightLowerLeg': (6, 0, 0), 'RightFoot': (-2, 0, -3),
    'LeftUpperLeg': (-2, 0, -3), 'LeftLowerLeg': (6, 0, 0), 'LeftFoot': (-4, 0, 3),
}


def legs(stance, crouch):
    """Ноги и таз: stance — шаг (−1 правая вперёд … +1 правая назад), crouch — присед 0..1."""
    r_th = -26 * crouch + 22 * stance
    l_th = -26 * crouch - 22 * stance
    r_kn = 10 + 46 * crouch + max(0, -stance) * 18
    l_kn = 10 + 46 * crouch + max(0, stance) * 18
    return {
        'LowerTorso.pos': (0, -0.4 - 3.4 * crouch, 0.6 * crouch),
        'RightUpperLeg': (r_th, 0, 6 + 4 * crouch),
        'RightLowerLeg': (r_kn, 0, 0),
        'RightFoot': (-r_th - r_kn, 0, -6 - 4 * crouch),
        'LeftUpperLeg': (l_th, 0, -6 - 4 * crouch),
        'LeftLowerLeg': (l_kn, 0, 0),
        'LeftFoot': (-l_th - l_kn, 0, 6 + 4 * crouch),
    }


class Keys:
    """Позы во времени. key(t, ease, pose) ставит ключи всем уже встреченным костям (остальные держат прошлое
    значение) со сдвигом LAG (overlap=True). hold(dur) — микро-стоп: та же поза ещё dur секунд."""

    def __init__(self, anim):
        self.a = anim
        self.cur = {}
        self.last_t = {}
        self.t = 0.0

    def _put(self, bone, t, v, ease):
        t = max(0.0, min(self.a.length, t))
        lt = self.last_t.get(bone)
        if lt is not None and t <= lt + 0.01:
            t = min(self.a.length, lt + 0.02)
            if t <= lt:
                return
        self.last_t[bone] = t
        if bone.endswith('.pos'):
            self.a.pos(bone[:-4], t, v, ease)
        else:
            self.a.rot(bone, t, v, ease)

    def key(self, t, ease, pose, overlap=True):
        self.cur.update(pose)
        for b in sorted(self.cur):
            lag = LAG.get(b, 0.0) if overlap and t > 0 else 0.0
            self._put(b, t + lag, self.cur[b], ease)
        self.t = t
        return self

    def hold(self, dur):
        t = self.t + dur
        for b in sorted(self.cur):
            self._put(b, max(t, self.last_t.get(b, 0) + 0.02), self.cur[b], 'linear')
        self.t = t
        return self


def M(*poses):
    out = {}
    for p in poses:
        out.update(p)
    return out


def arms(r_up, r_low, l_up, l_low, r_hand=ZERO, l_hand=ZERO):
    return {'RightUpperArm': r_up, 'RightLowerArm': (r_low, 0, 0), 'RightHand': r_hand,
            'LeftUpperArm': l_up, 'LeftLowerArm': (l_low, 0, 0), 'LeftHand': l_hand}


def body(pelvis_yaw=0, chest=(0, 0, 0), head=(0, 0, 0)):
    return {'LowerTorso': (0, pelvis_yaw, 0), 'UpperTorso': chest, 'Head': head}


# =========================================================================== Красный

def red_cast():
    """Обычный Красный, 0,9 с; выстрел (маркер fire) — 0,65 с = тик R_FIRE 13.
    Замах: шаг правой назад, присед, скрут вправо, правая кисть у груди (видна от первого лица), левая вперёд.
    Дуга: перед выпадом кисть уходит чуть наружу, потом вперёд. Выпад: скрут влево, рука прямо в прицел.
    Микро-стоп 0,1 с, отдача (кисть подбрасывает), возврат."""
    a = Anim('red_cast', 0.9, props={'priority': 'action', 'fade_in': 0.08, 'fade_out': 0.25,
                                     'look': 0.6, 'aim': 0.6, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.16, 'easeOutCubic', M(legs(0.6, 0.55), body(16, (8, 22, -3), (-4, -30, 2)),
                                   arms((-34, -26, 22), -100, (-46, 14, -14), -40, (0, 0, -20), (0, 0, 10))))
    k.key(0.4, 'easeInOutSine', M(legs(0.65, 0.7), body(18, (11, 26, -4), (-5, -34, 2)),
                                  arms((-32, -30, 24), -104, (-50, 18, -16), -44, (0, 0, -24), (0, 0, 12))))
    # дуга: кисть уходит наружу и вверх (разгон к выпаду)
    k.key(0.56, 'easeInOutSine', M(legs(0.5, 0.65), body(10, (10, 18, -3), (-4, -24, 2)),
                                   arms((-58, -4, 34), -84, (-40, 10, -18), -40, (0, 0, -10), (0, 0, 8))))
    # выпад: рука прямо вперёд, корпус скручен влево
    k.key(0.65, 'easeInQuint', M(legs(-0.55, 0.45), body(-10, (12, -26, 2), (-2, 32, -2)),
                                  arms((-92, 6, -2), -4, (22, -10, -26), -30)))
    k.hold(0.1)
    # отдача: кисть подбрасывает, корпус откидывает
    k.key(0.8, 'easeOutBack', M(body(-8, (-6, -20, 3), (-6, 26, -2)),
                                arms((-118, 8, -2), -24, (14, -8, -22), -26, (0, 0, 12))))
    k.key(0.9, 'easeInOutSine', SETTLE, overlap=False)
    a.marker(0.16, 'spark')
    a.marker(0.65, 'fire')
    return a


def red_charge():
    """Удержание X (до 2 с, потом сам начинается Максимальный Красный): сжатие с нарастанием.
    Правая кисть у груди, левая накрывает её снизу, присед глубже, дрожь сильнее к концу."""
    a = Anim('red_charge', 2.0, loop='hold', props={'priority': 'action', 'fade_in': 0.12, 'fade_out': 0.2,
                                                    'look': 0.5, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.25, 'easeOutCubic', M(legs(0.55, 0.45), body(16, (10, 20, -2), (-6, -24, 0)),
                                   arms((-28, -30, 18), -90, (-24, 34, -12), -86, (0, 0, -30), (0, 0, 30))))
    t, i = 0.36, 0
    while t < 1.95:
        f = (t - 0.36) / 1.6
        amp = 0.6 + 2.8 * f
        s = 1 if i % 2 == 0 else -1
        k.key(round(t, 2), 'easeInOutSine', M(
            legs(0.55 + 0.1 * f, 0.45 + 0.4 * f),
            body(16 + 4 * f, (10 + 7 * f + s * amp * 0.4, 20 + 6 * f + s * amp, -2 + s * amp * 0.5),
                 (-6 - 3 * f, -24 - 4 * f - s * amp * 0.6, 0)),
            arms((-28 - 4 * f, -30, 18), -90 - 4 * f, (-24 - 4 * f, 34, -12), -86,
                 (0, 0, -30), (0, 0, 30))), overlap=False)
        t += 0.1
        i += 1
    return a


def max_red():
    """Максимальный Красный, 1,8 с (тики: аура 5, сгущение 15, шар 18, волна 22, выстрел 26, конец 36).
    Печать (0–0,5): разворот боком, правая рука поднята сбоку над плечом — открытый силуэт;
    сжатие (0,7–1,05): кисть по дуге к груди, глубокий присед, левая держит запястье;
    волна (1,1): рывком раскрыться; выстрел (1,3): выпад, рука в прицел, микро-стоп; отдача, возврат."""
    a = Anim('max_red', 1.8, props={'priority': 'action', 'fade_in': 0.1, 'fade_out': 0.4, 'look': 0.5,
                                    'aim': 0.45, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.25, 'easeOutBack', M(legs(0.4, 0.35), body(32, (2, 16, -6), (-6, -44, 0)),
                                  arms((-130, 30, -10), -38, (-34, 22, -16), -60, (0, 0, -16), (0, 0, 10))))
    k.key(0.5, 'easeInOutSine', M(body(34, (0, 18, -7), (-8, -46, 0)),
                                  arms((-138, 32, -12), -42, (-36, 24, -16), -62, (0, 0, -16), (0, 0, 10))))
    # дуга вниз к груди
    k.key(0.72, 'easeInOutSine', M(legs(0.5, 0.6), body(30, (10, 20, -4), (-8, -42, 0)),
                                   arms((-84, -6, 52), -80, (-30, 30, -10), -80, (0, 0, -22), (0, 0, 24))))
    k.key(0.88, 'easeOutCubic', M(legs(0.55, 0.95), body(28, (17, 22, -2), (-10, -40, 0)),
                                  arms((-30, -30, 22), -92, (-24, 38, -6), -86, (0, 0, -26), (0, 0, 30))))
    k.key(1.02, 'easeInOutSine', M(legs(0.55, 1.0), body(28, (20, 24, -3), (-11, -42, 0)),
                                   arms((-28, -32, 23), -94, (-22, 38, -6), -86, (0, 0, -26), (0, 0, 30))))
    # волна: раскрыться рывком
    k.key(1.1, 'easeOutExpo', M(legs(0.5, 0.55), body(26, (-10, 26, -4), (-14, -40, 0)),
                                arms((-70, -6, 50), -70, (-44, 10, -34), -40)))
    k.key(1.22, 'easeInOutSine', M(legs(0.55, 0.75), body(24, (4, 28, -3), (-8, -38, 0)),
                                   arms((-50, -18, 40), -100, (-38, 20, -26), -60, (0, 0, -20))))
    # выстрел
    k.key(1.3, 'easeInQuint', M(legs(-0.7, 0.6), body(-6, (14, -24, 2), (-2, 30, -2)),
                                 arms((-92, 6, -2), -2, (28, -12, -30), -24)))
    k.hold(0.12)
    k.key(1.52, 'easeOutBack', M(legs(-0.3, 0.45), body(-4, (-12, -18, 3), (-10, 22, -2)),
                                 arms((-126, 8, 2), -26, (16, -10, -28), -24, (0, 0, 14))))
    k.key(1.8, 'easeInOutSine', SETTLE, overlap=False)
    a.marker(0.25, 'aura')
    a.marker(1.1, 'shockwave')
    a.marker(1.3, 'fire')
    return a


# =========================================================================== Синий

def blue_charge():
    """Удержание Z (1 с до Максимального Синего): руки сходятся перед грудью, пальцы к пальцам, корпус чуть назад,
    присед нарастает. Держится последним кадром, пока не начнётся Максимальный Синий или не отпустят."""
    a = Anim('blue_charge', 1.0, loop='hold', props={'priority': 'action', 'fade_in': 0.12, 'fade_out': 0.25,
                                                     'look': 0.8, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.3, 'easeOutCubic', M(legs(0.3, 0.35), body(-8, (6, -10, 0), (-2, 12, 0)),
                                  arms((-50, -34, 10), -70, (-50, 34, -10), -70, (0, 0, -30), (0, 0, 30))))
    k.key(0.65, 'easeInOutSine', M(legs(0.35, 0.55), body(-10, (9, -12, 0), (-3, 14, 0)),
                                   arms((-58, -40, 8), -62, (-58, 40, -8), -62, (0, 0, -36), (0, 0, 36))))
    k.key(1.0, 'easeInOutSine', M(legs(0.35, 0.65), body(-10, (10, -12, 0), (-4, 14, 0)),
                                  arms((-60, -42, 8), -60, (-60, 42, -8), -60, (0, 0, -38), (0, 0, 38))))
    return a


def max_blue():
    """Максимальный Синий, вход 2 с (сфера формируется 40 тиков): из сведённых рук — рывок назад (замах),
    потом обе ладони толкают вперёд-вверх, на самом раскрытии сферы — микро-стоп и отдача; дальше max_blue_hold."""
    a = Anim('max_blue', 2.0, props={'priority': 'action', 'fade_in': 0.15, 'fade_out': 0.3, 'look': 0.8,
                                     'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', M(legs(0.35, 0.65), body(-10, (10, -12, 0), (-4, 14, 0)),
                           arms((-60, -42, 8), -60, (-60, 42, -8), -60, (0, 0, -38), (0, 0, 38))), overlap=False)
    # замах: руки к груди, корпус назад, глубже присед
    k.key(0.35, 'easeOutCubic', M(legs(0.45, 0.85), body(-6, (-8, -6, 0), (-8, 8, 0)),
                                   arms((-18, -30, 26), -100, (-18, 30, -26), -100, (0, 0, -20), (0, 0, 20))))
    k.key(0.55, 'easeInOutSine', M(body(-6, (-11, -6, 0), (-10, 8, 0)),
                                   arms((-14, -28, 28), -106, (-14, 28, -28), -106)))
    # дуга: локти наружу-вверх по пути вперёд
    k.key(0.7, 'easeInCubic', M(legs(0.1, 0.6), body(-2, (2, 0, 0), (-4, 2, 0)),
                                arms((-80, -10, 40), -70, (-80, 10, -40), -70)))
    # толчок обеими ладонями вперёд-вверх
    k.key(0.82, 'easeInQuint', M(legs(-0.35, 0.55), body(2, (14, 4, 0), (0, -2, 0)),
                                  arms((-112, -18, 10), -6, (-112, 18, -10), -6, (0, 0, -10), (0, 0, 10))))
    k.hold(0.12)
    # отдача: корпус откидывает, руки чуть вверх
    k.key(1.1, 'easeOutBack', M(legs(-0.2, 0.45), body(0, (-6, 2, 0), (-8, 0, 0)),
                                arms((-114, -14, 24), -18, (-114, 14, -24), -18)))
    # удержание: ладони держат сферу, напряжение
    k.key(1.5, 'easeInOutSine', M(legs(-0.2, 0.5), body(0, (6, 0, 0), (-4, 0, 0)),
                                  arms((-104, -20, 12), -22, (-104, 20, -12), -22, (0, 0, -16), (0, 0, 16))))
    k.key(2.0, 'easeInOutSine', M(body(0, (8, 2, -1), (-3, 0, 0)),
                                  arms((-100, -22, 12), -26, (-100, 22, -12), -26)))
    a.marker(0.82, 'push')
    return a


def max_blue_hold():
    """Пока сфера жива: ладони держат её перед собой, тело дышит и дрожит от напряжения (цикл 1,6 с,
    руки отстают от корпуса — overlapping), голова следит за прицелом."""
    a = Anim('max_blue_hold', 1.6, loop=True, props={'priority': 'action', 'fade_in': 0.25, 'fade_out': 0.35,
                                                     'look': 0.85, 'fp': 'arms'})
    k = Keys(a)
    n = 8
    for i in range(n + 1):
        t = 1.6 * i / n
        ph = i / n
        import math
        br = math.sin(2 * math.pi * ph)
        tr = math.sin(2 * math.pi * ph * 4)  # мелкая дрожь корпуса (руки идут вместе с ним, а не навстречу друг другу)
        lag = math.sin(2 * math.pi * (ph - 0.12))
        k.key(round(t, 3), 'catmullrom', M(
            legs(-0.2, 0.5 + 0.06 * br),
            body(2 * br, (8 + 2 * br, 2 + 1.2 * tr, -1), (-3 - br, 0, 0)),
            arms((-100 - 4 * lag, -22, 12 + 2 * lag), -26 - 4 * lag, (-100 - 4 * lag, 22, -12 - 2 * lag),
                 -26 - 4 * lag, (0, 0, -16), (0, 0, 16))), overlap=False)
    return a


# =========================================================================== Фиолетовый (обычный, G)

def hollow_purple():
    """Каст Полого Фиолетового, 5 с (castProgress 0..1 с сервера, CAST_TICKS 100):
    0–1,6 с — Синий и Красный рождаются за спиной по бокам: руки раскрываются в стороны-назад, ладони наружу,
              грудь вперёд (широкий открытый силуэт);
    1,6–2,9 с — шары сходятся за спиной: руки по дуге сводятся вперёд, корпус собирается;
    2,9–3,7 с — Фиолетовый рождается впереди: обе ладони выбрасываются вперёд (микро-стоп);
    3,7–5 с — сфера растёт: ладони держат её, напряжение, лёгкая дрожь (держится до выстрела)."""
    a = Anim('hollow_purple', 5.0, loop='hold', props={'priority': 'action', 'fade_in': 0.2, 'fade_out': 0.3,
                                                       'look': 0.7, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.5, 'easeOutCubic', M(legs(0.2, 0.3), body(0, (-4, 0, 0), (-6, 0, 0)),
                                  arms((-20, 30, 70), -20, (-20, -30, -70), -20, (0, -30, 0), (0, 30, 0))))
    k.key(1.6, 'easeInOutSine', M(legs(0.2, 0.4), body(0, (-10, 0, 0), (-10, 0, 0)),
                                  arms((-10, 40, 84), -14, (-10, -40, -84), -14, (0, -40, 0), (0, 40, 0))))
    # сведение по дуге: руки идут вперёд через стороны
    k.key(2.3, 'easeInOutCubic', M(legs(0.3, 0.55), body(0, (4, 0, 0), (-4, 0, 0)),
                                   arms((-60, 10, 60), -40, (-60, -10, -60), -40)))
    k.key(2.9, 'easeInOutSine', M(legs(0.35, 0.7), body(0, (10, 0, 0), (-2, 0, 0)),
                                  arms((-40, -40, 14), -90, (-40, 40, -14), -90, (0, 0, -30), (0, 0, 30))))
    # рождение впереди: толчок обеими ладонями
    k.key(3.1, 'easeInQuint', M(legs(-0.3, 0.5), body(0, (14, 0, 0), (0, 0, 0)),
                                 arms((-96, -16, 8), -6, (-96, 16, -8), -6, (0, 0, -8), (0, 0, 8))))
    k.hold(0.12)
    k.key(3.6, 'easeOutBack', M(legs(-0.2, 0.45), body(0, (2, 0, 0), (-4, 0, 0)),
                                arms((-104, -20, 14), -18, (-104, 20, -14), -18)))
    t = 3.9
    i = 0
    while t <= 4.95:
        s_ = 1 if i % 2 == 0 else -1
        f = (t - 3.9) / 1.05
        k.key(round(t, 2), 'easeInOutSine', M(legs(-0.2, 0.5 + 0.15 * f), body(0, (6 + 3 * f, 0, 0), (-3, 0, 0)),
                                             arms((-100 - 3 * f, -22, 14), -22, (-100 - 3 * f, 22, -14),
                                                  -22)), overlap=False)
        t += 0.15
        i += 1
    a.marker(3.1, 'birth')
    return a


def hollow_purple_fire():
    """Выстрел Фиолетового: из удержания — правая ладонь коротким толчком вперёд (щелчок), левая уходит назад,
    корпус скручивается, микро-стоп, отдача и возврат."""
    a = Anim('hollow_purple_fire', 0.9, props={'priority': 'action', 'fade_in': 0.05, 'fade_out': 0.3,
                                               'look': 0.6, 'aim': 0.5, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', M(legs(-0.2, 0.65), body(0, (9, 0, 0), (-3, 0, 0)),
                           arms((-103, -22, 14), -22, (-103, 22, -14), -22)), overlap=False)
    k.key(0.1, 'easeOutCubic', M(legs(0.3, 0.7), body(10, (6, 14, 0), (-2, -12, 0)),
                                 arms((-70, -30, 20), -70, (-80, 20, -16), -50)))
    k.key(0.2, 'easeInQuint', M(legs(-0.6, 0.55), body(-12, (14, -22, 2), (0, 26, -2)),
                                 arms((-94, 6, -2), -2, (20, -12, -28), -30)))
    k.hold(0.12)
    k.key(0.48, 'easeOutBack', M(legs(-0.3, 0.45), body(-8, (-6, -16, 3), (-6, 20, 0)),
                                 arms((-112, 8, 0), -20, (12, -10, -24), -26)))
    k.key(0.9, 'easeInOutSine', SETTLE, overlap=False)
    a.marker(0.2, 'fire')
    return a


# =========================================================================== Простая территория, RCT, телепорт

UPPER = ('UpperTorso', 'Head', 'RightUpperArm', 'RightLowerArm', 'RightHand', 'LeftUpperArm', 'LeftLowerArm',
         'LeftHand')


def upper(pose):
    return {b: v for b, v in pose.items() if b in UPPER}


def simple_domain():
    """Простая территория, вход 1 с: присед, правая ладонь вниз над землёй (круг расходится от руки), левая
    у груди ладонью вперёд; микро-стоп; дальше simple_domain_hold (только верх тела — ходить можно)."""
    a = Anim('simple_domain', 1.0, props={'priority': 'action', 'fade_in': 0.1, 'fade_out': 0.3, 'look': 0.6,
                                          'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.2, 'easeOutCubic', M(legs(0.4, 0.4), body(10, (-6, 10, 0), (-4, -8, 0)),
                                  arms((-20, 10, 40), -60, (-40, 20, -20), -80, (0, 0, -20), (0, 0, 20))))
    k.key(0.42, 'easeInQuint', M(legs(0.5, 1.0), body(14, (22, 12, -2), (8, -10, 0)),
                                  arms((-40, -10, 16), -6, (-56, 36, -10), -86, (-40, 0, 0), (0, 0, 30))))
    k.hold(0.14)
    k.key(1.0, 'easeInOutSine', M(legs(0.3, 0.45), body(6, (8, 6, 0), (0, -4, 0)),
                                  arms((-34, -36, 14), -80, (-46, 36, -10), -84, (0, 0, -30), (0, 0, 30))))
    a.marker(0.42, 'ground')
    return a


def simple_domain_hold():
    """Держит простую территорию: ладони сложены у груди (печать), корпус чуть вперёд, дыхание. Только верх тела."""
    import math
    a = Anim('simple_domain_hold', 2.0, loop=True, props={'priority': 'action', 'fade_in': 0.3, 'fade_out': 0.3,
                                                          'look': 0.9, 'fp': 'none'})
    k = Keys(a)
    for i in range(9):
        ph = i / 8
        br = math.sin(2 * math.pi * ph)
        lag = math.sin(2 * math.pi * (ph - 0.1))
        k.key(round(2.0 * ph, 3), 'catmullrom', upper(M(body(0, (8 + 1.5 * br, 6, 0), (0, -4, 0)),
                                                        arms((-34 - 2 * lag, -36, 14), -80 - 2 * lag,
                                                             (-46 - 2 * lag, 36, -10), -84 - 2 * lag,
                                                             (0, 0, -30), (0, 0, 30)))), overlap=False)
    return a


def rct():
    """Обратная техника (лечение), 1,2 с: ладонь к груди, вдох — корпус раскрывается назад, голова вверх,
    руки разводятся в стороны ладонями вверх (выдох), возврат."""
    a = Anim('rct', 1.2, props={'priority': 'action', 'fade_in': 0.1, 'fade_out': 0.3, 'look': 0.3, 'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.25, 'easeOutCubic', M(legs(0.0, 0.3), body(0, (10, 0, 0), (8, 0, 0)),
                                   arms((-44, -40, 10), -96, (-44, 40, -10), -96, (0, 0, -30), (0, 0, 30))))
    k.hold(0.1)
    k.key(0.6, 'easeOutBack', M(legs(0.0, 0.1), body(0, (-12, 0, 0), (-18, 0, 0)),
                                arms((-30, 30, 56), -20, (-30, -30, -56), -20, (0, -60, 0), (0, 60, 0))))
    k.key(0.85, 'easeInOutSine', M(body(0, (-10, 0, 0), (-16, 0, 0)),
                                   arms((-28, 32, 58), -22, (-28, -32, -58), -22)))
    k.key(1.2, 'easeInOutSine', SETTLE, overlap=False)
    a.marker(0.6, 'heal')
    return a


def teleport():
    """Телепорт, 0,6 с: короткий присед и наклон вперёд, два пальца у лица (жест) — рывок — на новом месте
    приземление с отдачей. Мгновенный перенос в коде — на маркере blink (0,18 с)."""
    a = Anim('teleport', 0.6, props={'priority': 'action', 'fade_in': 0.04, 'fade_out': 0.25, 'look': 0.5,
                                     'fp': 'arms'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.12, 'easeOutCubic', M(legs(0.2, 0.7), body(0, (16, 0, 0), (2, 0, 0)),
                                   arms((-70, -36, 12), -110, (14, 0, -20), -30, (0, 0, -20))))
    k.key(0.2, 'easeInQuint', M(legs(-0.2, 0.2), body(0, (24, 0, 0), (-6, 0, 0)),
                                 arms((30, -6, 24), -20, (30, 6, -24), -20)))
    k.key(0.32, 'easeOutBack', M(legs(0.1, 0.85), body(0, (12, 0, 0), (0, 0, 0)),
                                 arms((-30, 10, 40), -40, (-30, -10, -40), -40)))
    k.key(0.6, 'easeInOutSine', SETTLE, overlap=False)
    a.marker(0.18, 'blink')
    return a


# =========================================================================== Максимальный Фиолетовый (кат-сцена)

def max_purple():
    """Кат-сцена 20,4 с (тики MaximumPurple: Синий в руке 14, вихрь 16, бросок 40, Красный 58, выстрел 69,
    небо 100, сближение 128, погоня 160, подъём 228–282, темнота 283, космос 305, слияние 336, поза 360, удар 362).
    Тело всё время работает: бросает Синий, стреляет Красным, дирижирует шарами (руки ведут их по кругу),
    поднимается раскрывшись, собирает шары в ладонях и встаёт в позу Годжо. Камера — кат-сцены, поэтому fp=none."""
    import math
    a = Anim('max_purple', 20.4, loop='hold', props={'priority': 'action4', 'fade_in': 0.15, 'fade_out': 0.6,
                                                     'look': 0.15, 'face': 0, 'fp': 'none'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    # Синий в правой ладони
    k.key(0.7, 'easeOutCubic', M(legs(0.2, 0.3), body(8, (4, 10, 0), (6, -12, 0)),
                                  arms((-60, -20, 16), -50, (-10, 10, -14), -20, (0, 50, 0))))
    # вихрь: присед, рука с Синим отведена вниз-назад (замах)
    k.key(1.3, 'easeInOutCubic', M(legs(0.5, 1.0), body(24, (18, 26, -4), (4, -24, 0)),
                                   arms((10, 10, 30), -40, (-40, 30, -10), -60, (0, 30, 0))))
    k.key(1.85, 'easeInOutSine', M(legs(0.55, 1.05), body(26, (20, 28, -5), (2, -26, 0)),
                                   arms((16, 12, 32), -36, (-44, 32, -10), -60)))
    # бросок вверх: рука по дуге через сторону над головой, тело разгибается
    k.key(1.95, 'easeInSine', M(legs(0.3, 0.6), body(14, (4, 16, -4), (-10, -14, 0)),
                                arms((-80, 10, 60), -30, (-30, 20, -20), -40)))
    k.key(2.05, 'easeInQuint', M(legs(-0.1, 0.0), body(-4, (-16, -6, 2), (-34, 6, 0)),
                                  arms((-170, 0, 10), -4, (-10, -10, -30), -20, (0, 0, 0))))
    k.hold(0.15)
    k.key(2.8, 'easeOutCubic', M(legs(0.0, 0.2), body(0, (-8, 0, 0), (-30, 0, 0)),
                                 arms((-150, 0, 20), -16, (-6, 0, -12), -14)))
    # Красный у лица в левой ладони
    k.key(3.1, 'easeInOutCubic', M(legs(-0.2, 0.35), body(-10, (6, -14, 2), (-6, 16, 0)),
                                   arms((-20, 0, 16), -30, (-100, 30, -30), -110, ZERO, (0, -40, 0))))
    k.key(3.4, 'easeInOutSine', M(body(-12, (8, -16, 2), (-8, 18, 0)),
                                  arms((-18, 0, 16), -30, (-96, 34, -28), -116)))
    # выстрел Красного вверх
    k.key(3.45, 'easeInQuint', M(legs(-0.1, 0.1), body(-4, (-14, -6, 0), (-36, 6, 0)),
                                  arms((-14, 0, 20), -20, (-172, 0, -8), -4)))
    k.hold(0.12)
    k.key(4.2, 'easeOutCubic', M(legs(0.0, 0.25), body(0, (-6, 0, 0), (-32, 0, 0)),
                                 arms((-20, 0, 18), -24, (-140, 0, -20), -20)))
    # небо: обе руки ведут шары (дирижирует), дыхание, перенос веса
    k.key(5.0, 'easeInOutSine', M(legs(0.15, 0.3), body(6, (-4, 8, 0), (-30, -6, 0)),
                                  arms((-120, -10, 40), -30, (-70, 10, -40), -40, (0, 30, 0), (0, -30, 0))))
    k.key(6.4, 'easeInOutSine', M(legs(-0.15, 0.35), body(-6, (-6, -8, 0), (-34, 6, 0)),
                                  arms((-100, -20, 30), -40, (-120, 20, -30), -30, (0, 30, 0), (0, -30, 0))))
    # погоня: ладони кружат друг вокруг друга (шары гоняются)
    t = 8.0
    while t < 11.35:
        ph = (t - 8.0) / 1.1 * 2 * math.pi
        k.key(round(t, 2), 'catmullrom', M(
            legs(0.1 * math.sin(ph), 0.3),
            body(6 * math.sin(ph), (-6 + 2 * math.cos(ph), 8 * math.sin(ph), 0), (-34, -6 * math.sin(ph), 0)),
            arms((-96 + 18 * math.sin(ph), 4, 42 + 12 * math.cos(ph)), -30 - 10 * math.cos(ph),
                 (-96 - 18 * math.sin(ph), -4, -42 + 12 * math.cos(ph)), -30 + 10 * math.cos(ph),
                 (0, 30, 0), (0, -30, 0))), overlap=False)
        t += 0.275
    # подъём: раскрылся, руки в стороны-вниз, ноги вместе, носки вниз, голова вверх
    k.key(11.7, 'easeInOutCubic', M({'LowerTorso.pos': ZERO, 'RightUpperLeg': (8, 0, 4), 'RightLowerLeg': (16, 0, 0),
                                      'RightFoot': (20, 0, 0), 'LeftUpperLeg': (-4, 0, -4), 'LeftLowerLeg': (24, 0, 0),
                                      'LeftFoot': (20, 0, 0)},
                                     body(0, (-10, 0, 0), (-30, 0, 0)),
                                     arms((-20, 20, 50), -16, (-20, -20, -50), -16, (0, -40, 0), (0, 40, 0))))
    k.key(13.2, 'easeInOutSine', M(body(0, (-14, 0, 0), (-36, 0, 0)),
                                   arms((-26, 24, 58), -18, (-26, -24, -58), -18)))
    k.key(14.1, 'easeInOutSine', M(body(0, (-12, 0, 0), (-28, 0, 0)),
                                   arms((-30, 24, 56), -20, (-30, -24, -56), -20)))
    # темнота: руки крест-накрест у груди, собрался
    k.key(14.8, 'easeInOutCubic', M(body(0, (10, 0, 0), (6, 0, 0)),
                                    arms((-50, -50, 10), -100, (-50, 50, -10), -100, (0, 0, -30), (0, 0, 30))))
    # космос: ладони расходятся и медленно сводятся (шары сходятся)
    k.key(15.6, 'easeOutCubic', M(body(0, (-4, 0, 0), (-8, 0, 0)),
                                  arms((-80, 0, 40), -20, (-80, 0, -40), -20, (0, 40, 0), (0, -40, 0))))
    k.key(16.8, 'easeInCubic', M(body(0, (6, 0, 0), (-4, 0, 0)),
                                 arms((-84, -30, 10), -40, (-84, 30, -10), -40, (0, 0, -40), (0, 0, 40))))
    k.hold(0.1)
    # за спиной огромная сфера — ладони держат её, корпус чуть назад
    k.key(17.5, 'easeOutBack', M(body(0, (-8, 0, 0), (-12, 0, 0)),
                                 arms((-100, -14, 30), -20, (-100, 14, -30), -20)))
    # поза Годжо: правая рука вперёд «два пальца», левая опущена, корпус скручен, голова чуть набок
    k.key(18.0, 'easeInQuint', M(body(-18, (6, -20, 4), (2, 22, -8)),
                                  arms((-90, 4, 0), -2, (-6, -10, -14), -10, (0, 0, 0), ZERO)))
    k.hold(0.3)
    k.key(19.2, 'easeInOutSine', M(body(-16, (4, -18, 3), (0, 20, -6)),
                                   arms((-86, 4, 2), -6, (-6, -10, -14), -12)))
    k.key(20.4, 'easeInOutSine', M(body(-14, (2, -16, 2), (-2, 18, -4)),
                                   arms((-80, 4, 4), -10, (-6, -10, -14), -12)))
    for t_, m in ((0.7, 'blue'), (2.05, 'throw'), (2.9, 'red'), (3.45, 'red_fire'), (18.0, 'pose')):
        a.marker(t_, m)
    return a


# =========================================================================== Расширение территории

def domain_cast():
    """Расширение территории, 10,8 с (катсцена DomainExpansionClient; камера ведёт правую кисть).
    Знаменитый жест Годжо: правая кисть по дуге поднимается к лицу (скрещённые пальцы перед глазами), к 3 с
    замирает; левая рука свободно опущена, корпус чуть скручен, голова слегка наклонена к руке.
    Дальше живое удержание (дыхание, лёгкая дрожь пальцев) до конца катсцены."""
    import math
    a = Anim('domain_cast', 10.8, loop='hold', props={'priority': 'action', 'fade_in': 0.2, 'fade_out': 0.4,
                                                      'look': 0.2, 'face': 0, 'fp': 'none'})
    k = Keys(a)
    k.key(0.0, 'linear', REST)
    k.key(0.5, 'easeInOutSine', M(legs(0.1, 0.15), body(-4, (2, -4, 0), (2, 4, 0)),
                                  arms((-14, 0, 14), -20, (4, 0, -10), -10)))
    # дуга: кисть уходит наружу-вверх
    k.key(1.4, 'easeInOutCubic', M(body(-8, (0, -8, 1), (0, 8, 0)),
                                   arms((-62, 14, 40), -40, (6, 4, -12), -12, (0, 20, 0))))
    k.key(2.4, 'easeInOutCubic', M(body(-10, (2, -10, 2), (6, 10, -2)),
                                   arms((-78, -28, 18), -66, (6, 6, -12), -14, (0, -20, 0))))
    k.key(3.0, 'easeOutBack', M(body(-10, (3, -10, 2), (8, 12, -4)),
                                arms((-80, -40, 10), -70, (6, 6, -12), -14, (0, -30, 0))))
    k.hold(0.15)
    t = 3.6
    i = 0
    while t <= 10.75:
        ph = (t - 3.6) / 2.4 * 2 * math.pi
        br = math.sin(ph)
        tr = 0.6 if i % 2 == 0 else -0.6
        k.key(round(t, 2), 'catmullrom', M(
            legs(0.1, 0.15 + 0.03 * br), body(-10, (3 + 1.2 * br, -10, 2), (8 + 0.8 * br, 12, -4)),
            arms((-80 - 1.0 * br, -40, 10), -70 - br, (6 - 2 * br, 6, -12), -14, (0, -30, 0))),
            overlap=False)
        t += 0.4
        i += 1
    a.marker(3.0, 'sign')
    return a


ALL = [red_cast, red_charge, max_red, blue_charge, max_blue, max_blue_hold, hollow_purple, hollow_purple_fire,
       simple_domain, simple_domain_hold, rct, teleport, max_purple, domain_cast]
MADE_HERE = {f.__name__ for f in ALL}


def check(anims):
    """Руки от первого лица: кисть не должна оказываться внутри головы (рука во весь экран)."""
    import geckosim as gs
    import numpy as np
    from where import points
    bad = []
    for a in anims:
        if a.props.get('fp') == 'none':  # кат-сцены и клипы без рук от первого лица
            continue
        key = 'animation.player.' + a.name
        with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False) as f:
            json.dump({'format_version': '1.8.0', 'animations': {key: a.to_json()}}, f)
        an = gs.load_anims(f.name)[key]
        os.unlink(f.name)
        for i in range(int(a.length / 0.05) + 1):
            pose = {}
            for b, ch in gs.sample(an, i).items():
                if 'rotation' in ch:
                    x, y, z = ch['rotation']
                    pose[b] = (-np.degrees(x), -np.degrees(y), np.degrees(z))
                if 'position' in ch:
                    pose[b + '.pos'] = ch['position']
            p = points(pose)
            for h in ('RightHand', 'LeftHand'):
                d = np.linalg.norm(p[h] - p['Head'])
                if d < 0.3:
                    bad.append('%s t=%.2f %s у головы (%.2f блока)' % (a.name, i * 0.05, h, d))
    return bad


if __name__ == '__main__':
    anims = [f() for f in ALL]
    write('gojo.animation.json', anims)
    for b in check(anims):
        print('!', b)
