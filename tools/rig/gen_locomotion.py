"""Анимации передвижения игрока R15 (Blockbench, GeckoLib): стойка, шаг, бег, сверхбег, присед, прыжок, падение,
приземление, плавание, лазание, сидение, полёт на элитрах, сон, движения мода (зарядка, взлёт, полёт, дэши)
и позы рук с предметами. Всё это играет скрипт Animate (rbx/AnimateScript.java).

  python3 tools/rig/gen_locomotion.py   →  assets/jujutsu_neon/animations/player/locomotion.animation.json

Циклы шага: правая стопа встаёт на землю в начале цикла, левая — в середине (общая фаза у ходьбы, бега,
сверхбега и шага в приседе). Скорость, под которую нарисован цикл, — @move_speed (блоки/с).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anim_dsl import Anim, leg_ik, write  # noqa: E402

TAU = 2 * math.pi


# ---------------------------------------------------------------- периодические кривые по опорным точкам

def periodic(points):
    """Замкнутый сплайн Катмулла-Рома через (фаза, значение); возвращает f(фаза)."""
    pts = sorted(points)
    n = len(pts)

    def f(ph):
        ph %= 1.0
        i = n - 1
        for k in range(n):
            if pts[k][0] > ph:
                i = k - 1
                break
        p1 = pts[i % n]
        p2 = pts[(i + 1) % n]
        p0 = pts[(i - 1) % n]
        p3 = pts[(i + 2) % n]
        t0, t1 = p1[0], p2[0]
        if t1 <= t0:
            t1 += 1.0
        x = ph if ph >= t0 else ph + 1.0
        t = (x - t0) / (t1 - t0) if t1 > t0 else 0.0
        a, b, c, d = p0[1], p1[1], p2[1], p3[1]
        return 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t ** 3)

    return f


def shifted(f, s):
    return lambda ph: f((ph + s) % 1.0)


def const(v):
    return lambda ph: v


# ---------------------------------------------------------------- цикл шага

class Gait:
    """Описание цикла шага опорными точками (градусы вперёд/сгиб) для правой стороны; левая — со сдвигом 0.5."""

    def __init__(self, thigh, knee, foot, arm, elbow, bob, pelvis_yaw, chest_yaw, chest_roll=1.5, pelvis_roll=2.0,
                 lean=3.0, pelvis_tilt=0.0, arm_out=5.0, hand=4.0, head_nod=1.0, lean_wave=1.0):
        self.thigh, self.knee, self.foot = periodic(thigh), periodic(knee), periodic(foot)
        self.arm, self.elbow = periodic(arm), periodic(elbow)
        self.bob = bob
        self.pelvis_yaw, self.chest_yaw = pelvis_yaw, chest_yaw
        self.chest_roll, self.pelvis_roll = chest_roll, pelvis_roll
        self.lean, self.pelvis_tilt, self.lean_wave = lean, pelvis_tilt, lean_wave
        self.arm_out, self.hand, self.head_nod = arm_out, hand, head_nod

    def build(self, a, samples=16):
        def pelvis_roll(ph):
            return self.pelvis_roll * math.cos(TAU * (ph - 0.25))

        def pelvis_yaw(ph):
            return -self.pelvis_yaw * math.cos(TAU * ph)

        a.cycle('LowerTorso', 'position', lambda ph: (0, self.bob(ph), 0), samples)
        a.cycle('LowerTorso', 'rotation', lambda ph: (self.pelvis_tilt, pelvis_yaw(ph), pelvis_roll(ph)), samples)
        a.cycle('UpperTorso', 'rotation', lambda ph: (
            self.lean - self.pelvis_tilt + self.lean_wave * math.cos(2 * TAU * ph),
            (self.chest_yaw + self.pelvis_yaw) * math.cos(TAU * ph),
            -self.chest_roll * math.cos(TAU * (ph - 0.25)) - pelvis_roll(ph)), samples)
        a.cycle('Head', 'rotation', lambda ph: (-self.head_nod * math.cos(2 * TAU * (ph - 0.05)) - self.lean * 0.5,
                                                  -self.chest_yaw * math.cos(TAU * ph) * 0.5, 0), samples)
        for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
            def thigh(ph, sh=sh):
                return self.thigh((ph + sh) % 1)

            def knee(ph, sh=sh):
                return self.knee((ph + sh) % 1)

            def foot(ph, sh=sh):
                return self.foot((ph + sh) % 1)

            # ноги: таз наклонён и крутится — бедро компенсирует, чтобы шаг шёл прямо
            a.cycle(side + 'UpperLeg', 'rotation', lambda ph, thigh=thigh, s=s: (
                -(thigh(ph) + self.pelvis_tilt), -pelvis_yaw(ph) * 0.6, s * 2.0 - pelvis_roll(ph) * 0.8), samples)
            a.cycle(side + 'LowerLeg', 'rotation', lambda ph, knee=knee: (knee(ph), 0, 0), samples)
            a.cycle(side + 'Foot', 'rotation', lambda ph, thigh=thigh, knee=knee, foot=foot: (
                (thigh(ph) - knee(ph)) - foot(ph), 0, 0), samples)

            # руки: противоположно ногам своей стороны
            def arm(ph, sh=sh):
                return self.arm((ph + sh) % 1)

            def elbow(ph, sh=sh):
                return self.elbow((ph + sh) % 1)

            a.cycle(side + 'UpperArm', 'rotation', lambda ph, arm=arm, s=s: (
                -arm(ph), -s * 0.12 * max(0.0, arm(ph)), s * self.arm_out), samples)
            a.cycle(side + 'LowerArm', 'rotation', lambda ph, elbow=elbow: (-elbow(ph), 0, 0), samples)
            a.cycle(side + 'Hand', 'rotation', lambda ph, arm=arm: (-self.hand * math.sin(TAU * 0.0) - 0.08 * arm(ph), 0, 0),
                    samples)
        return a


def walk():
    a = Anim('walk', 0.46, loop=True, props={'priority': 'core', 'move_speed': 4.317, 'fade_in': 0.2})
    g = Gait(
        thigh=[(0.0, 30), (0.15, 17), (0.35, -8), (0.55, -27), (0.70, -9), (0.85, 25)],
        knee=[(0.0, 4), (0.10, 14), (0.30, 6), (0.50, 22), (0.65, 58), (0.80, 38), (0.92, 9)],
        foot=[(0.0, 12), (0.10, 0), (0.45, 0), (0.58, -26), (0.70, -10), (0.86, 2), (0.95, 9)],
        arm=[(0.0, -24), (0.25, 2), (0.5, 30), (0.75, 4)],
        elbow=[(0.0, 12), (0.25, 17), (0.5, 30), (0.75, 21)],
        bob=lambda ph: -0.4 - 0.45 * math.cos(2 * TAU * (ph - 0.04)),
        pelvis_yaw=5, chest_yaw=6, lean=3, arm_out=5)
    return g.build(a)


def run():
    a = Anim('run', 0.40, loop=True, props={'priority': 'core', 'move_speed': 5.612, 'fade_in': 0.2})
    g = Gait(
        thigh=[(0.0, 36), (0.12, 19), (0.30, -22), (0.45, -15), (0.62, 26), (0.78, 50), (0.92, 42)],
        knee=[(0.0, 12), (0.10, 30), (0.28, 18), (0.42, 72), (0.55, 108), (0.70, 92), (0.82, 52), (0.93, 20)],
        foot=[(0.0, 8), (0.10, 0), (0.25, -10), (0.33, -34), (0.50, -18), (0.75, 0), (0.92, 9)],
        arm=[(0.0, -42), (0.25, 4), (0.5, 55), (0.75, 8)],
        elbow=[(0.0, 72), (0.25, 86), (0.5, 100), (0.75, 84)],
        bob=lambda ph: -0.7 - 0.8 * math.cos(2 * TAU * (ph - 0.12)),
        pelvis_yaw=8, chest_yaw=10, lean=11, pelvis_tilt=5, arm_out=8, chest_roll=1.0, lean_wave=1.5)
    return g.build(a)


def sprint():
    a = Anim('sprint', 0.32, loop=True, props={'priority': 'core', 'move_speed': 22, 'fade_in': 0.25})
    g = Gait(
        thigh=[(0.0, 46), (0.12, 22), (0.28, -30), (0.42, -26), (0.60, 30), (0.76, 72), (0.90, 58)],
        knee=[(0.0, 16), (0.10, 34), (0.26, 22), (0.40, 90), (0.54, 128), (0.68, 112), (0.82, 66), (0.93, 26)],
        foot=[(0.0, 6), (0.10, 0), (0.22, -14), (0.30, -40), (0.50, -24), (0.75, 0), (0.92, 8)],
        arm=[(0.0, -55), (0.25, 6), (0.5, 72), (0.75, 10)],
        elbow=[(0.0, 80), (0.25, 92), (0.5, 104), (0.75, 90)],
        bob=lambda ph: -1.6 - 1.0 * math.cos(2 * TAU * (ph - 0.12)),
        pelvis_yaw=9, chest_yaw=12, lean=30, pelvis_tilt=12, arm_out=9, chest_roll=0.8, lean_wave=2.0, head_nod=1.5)
    return g.build(a)


# ---------------------------------------------------------------- стойка

def idle(name='idle', look_around=False):
    L = 4.0 if not look_around else 3.5
    a = Anim(name, L, loop=True, props={'priority': 'core', 'fade_in': 0.3})

    def br(ph):  # дыхание
        return math.sin(TAU * ph)

    def sway(ph):
        return math.sin(TAU * (ph - 0.15))

    n = 8
    a.cycle('LowerTorso', 'position', lambda ph: (0.25 * sway(ph), 0.05 * br(ph), 0), n)
    a.cycle('LowerTorso', 'rotation', lambda ph: (0, 0, 0.8 * sway(ph)), n)
    a.cycle('UpperTorso', 'rotation', lambda ph: (1.2 + 0.9 * br(ph), 0, -0.8 * sway(ph) - 0.3 * br(ph)), n)
    if look_around:
        yaw = periodic([(0.0, 0), (0.12, 0), (0.28, -28), (0.45, -26), (0.62, 22), (0.80, 24), (0.94, 0)])
        pitch = periodic([(0.0, 0), (0.28, 4), (0.62, -3), (0.94, 0)])
        a.cycle('Head', 'rotation', lambda ph: (pitch(ph), yaw(ph), 0), 14)
    else:
        a.cycle('Head', 'rotation', lambda ph: (-0.8 * br(ph), 0, 0.4 * sway(ph)), n)
    for side, s in (('Right', 1), ('Left', -1)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s: (-2 - 1.6 * br(ph), 0, s * (4.5 + 1.2 * br(ph)) + 0.8 * sway(ph)), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph: (-9 - 2.5 * br(ph), 0, 0), n)
        a.cycle(side + 'Hand', 'rotation', lambda ph: (-3 - 1.5 * br(ph), 0, 0), n)
        # ноги стоят на месте, пока таз качается
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s: (0, 0, s * 1.8 - 0.8 * sway(ph)), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph, s=s: (0, 0, -s * 1.8), n)
    return a


# ---------------------------------------------------------------- присед

CROUCH_HIP = (0.0, -3.5, 1.2)    # таз: вниз и назад, пиксели
CROUCH_TILT = 16.0               # наклон таза вперёд
CROUCH_CHEST = 14.0              # дополнительный наклон груди


def crouch_legs(a, t, side, ankle_f, ankle_y, hip_y, hip_z, tilt, foot_abs=0.0, out=2.0, ease='catmullrom'):
    """Нога через IK: голеностоп в (ankle_f вперёд, ankle_y над землёй) при тазе на высоте hip_y и сдвиге hip_z назад."""
    f = ankle_f + hip_z           # таз отъехал назад → стопа впереди таза
    y = ankle_y - (12 + hip_y)
    thigh_x, knee_x = leg_ik(f, y)
    phi1 = -thigh_x
    s = 1 if side == 'Right' else -1
    a.rot(side + 'UpperLeg', t, (-(phi1 + tilt), 0, s * out), ease)
    a.rot(side + 'LowerLeg', t, (knee_x, 0, 0), ease)
    a.rot(side + 'Foot', t, ((phi1 - knee_x) - foot_abs, 0, -s * out * 0.5), ease)


def crouch_idle():
    a = Anim('crouch_idle', 3.0, loop=True, props={'priority': 'core', 'fade_in': 0.15})
    hx, hy, hz = CROUCH_HIP
    for i in range(9):
        t = 3.0 * i / 8
        br = math.sin(TAU * i / 8)
        a.pos('LowerTorso', t, (0, hy + 0.15 * br, hz))
        a.rot('LowerTorso', t, (CROUCH_TILT, 0, 0))
        a.rot('UpperTorso', t, (CROUCH_CHEST + 1.2 * br, 0, 0))
        a.rot('Head', t, (-6, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            crouch_legs(a, t, side, 0.0, 2.0, hy + 0.15 * br, hz, CROUCH_TILT)
            a.rot(side + 'UpperArm', t, (-(CROUCH_TILT + CROUCH_CHEST) * 0.9 - 6 - 1.5 * br, s * -6, s * 7))
            a.rot(side + 'LowerArm', t, (-26 - 2 * br, 0, 0))
            a.rot(side + 'Hand', t, (-6, 0, 0))
    return a


def crouch_walk():
    T = 0.9
    v_px = 1.295 / 0.9375 * 16     # скорость шага в приседе, пиксели модели в секунду
    duty = 0.56
    F = v_px * duty * T / 2         # полушаг опоры
    lift = 2.6
    a = Anim('crouch_walk', T, loop=True, props={'priority': 'core', 'move_speed': 1.295, 'fade_in': 0.15})
    hx, hy, hz = CROUCH_HIP
    n = 16

    def ankle(ph):
        """Голеностоп относительно точки под тазом: (вперёд, высота)."""
        if ph < duty:  # опора: стопа едет назад по земле
            u = ph / duty
            return F - 2 * F * u, 2.0
        u = (ph - duty) / (1 - duty)  # перенос: дугой вперёд
        e = 0.5 - 0.5 * math.cos(math.pi * u)
        return -F + 2 * F * e, 2.0 + lift * math.sin(math.pi * u) ** 1.2

    def foot_angle(ph):
        if ph < duty * 0.9:
            return 0.0
        u = (ph - duty * 0.9) / (1 - duty * 0.9)
        return -18 * math.sin(math.pi * min(1, u * 1.6)) if u < 0.62 else 6 * math.sin(math.pi * (u - 0.62) / 0.38)

    def bob(ph):
        return hy - 0.35 * math.cos(2 * TAU * (ph - 0.1))

    for i in range(n + 1):
        ph = (i % n) / n
        t = T * i / n
        a.pos('LowerTorso', t, (0, bob(ph), hz))
        a.rot('LowerTorso', t, (CROUCH_TILT, -4 * math.cos(TAU * ph), 1.2 * math.cos(TAU * (ph - 0.25))))
        a.rot('UpperTorso', t, (CROUCH_CHEST, 8 * math.cos(TAU * ph), -1.2 * math.cos(TAU * (ph - 0.25))))
        a.rot('Head', t, (-6, -3 * math.cos(TAU * ph), 0))
        for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
            p = (ph + sh) % 1.0
            f, y = ankle(p)
            crouch_legs(a, t, side, f, y, bob(ph), hz, CROUCH_TILT, foot_angle(p))
            swing = 14 * math.cos(TAU * (p - 0.5))
            a.rot(side + 'UpperArm', t, (-(CROUCH_TILT + CROUCH_CHEST) * 0.9 - 6 - swing, s * -6, s * 7))
            a.rot(side + 'LowerArm', t, (-26 - 6 * max(0, math.cos(TAU * (p - 0.5))), 0, 0))
            a.rot(side + 'Hand', t, (-6, 0, 0))
    return a


# ---------------------------------------------------------------- воздух

def jump():
    a = Anim('jump', 0.35, loop='hold', props={'priority': 'core', 'fade_in': 0.1})
    keys = [
        (0.0, dict(ut=(0, 0, 0), ra=(-20, 0, 10), rl=(-10, 0, 0), la=(-20, 0, -10), ll=(-10, 0, 0),
                   rth=(-8, 0, 2), rkn=(10, 0, 0), lth=(-4, 0, -2), lkn=(6, 0, 0), pos=(0, 0, 0))),
        # руки вверх и в стороны (как R15 jump): поворот Z поднимает руку через сторону
        (0.12, dict(ut=(-5, 0, 0), ra=(-22, 0, 142), rl=(-14, 0, 0), la=(-18, 0, -138), ll=(-16, 0, 0),
                    rth=(-28, 0, 3), rkn=(42, 0, 0), lth=(4, 0, -3), lkn=(16, 0, 0), pos=(0, 0.6, 0))),
        (0.35, dict(ut=(-3, 0, 0), ra=(-18, 0, 134), rl=(-18, 0, 0), la=(-14, 0, -130), ll=(-20, 0, 0),
                    rth=(-22, 0, 4), rkn=(36, 0, 0), lth=(0, 0, -4), lkn=(20, 0, 0), pos=(0, 0.3, 0))),
    ]
    for t, k in keys:
        a.pos('LowerTorso', t, k['pos'])
        a.rot('UpperTorso', t, k['ut'])
        a.rot('RightUpperArm', t, k['ra'])
        a.rot('RightLowerArm', t, k['rl'])
        a.rot('LeftUpperArm', t, k['la'])
        a.rot('LeftLowerArm', t, k['ll'])
        a.rot('RightUpperLeg', t, k['rth'])
        a.rot('RightLowerLeg', t, k['rkn'])
        a.rot('LeftUpperLeg', t, k['lth'])
        a.rot('LeftLowerLeg', t, k['lkn'])
        a.rot('RightFoot', t, (-(k['rth'][0] + k['rkn'][0]) * 0.3, 0, 0))
        a.rot('LeftFoot', t, (-(k['lth'][0] + k['lkn'][0]) * 0.3, 0, 0))
    return a


def fall():
    T = 0.9
    a = Anim('fall', T, loop=True, props={'priority': 'core', 'fade_in': 0.2})
    n = 8
    a.cycle('UpperTorso', 'rotation', lambda ph: (-3 + 1.2 * math.sin(TAU * ph), 0, 0), n)
    a.cycle('LowerTorso', 'position', lambda ph: (0, 0.2 * math.sin(TAU * ph), 0), n)
    for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s, sh=sh: (
            -16 + 6 * math.sin(TAU * (ph + sh)), 0, s * (124 + 7 * math.sin(TAU * (ph + sh + 0.2)))), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph, sh=sh: (-16 - 6 * math.sin(TAU * (ph + sh + 0.1)), 0, 0), n)
        a.cycle(side + 'Hand', 'rotation', lambda ph, sh=sh: (-8 * math.sin(TAU * (ph + sh + 0.25)), 0, 0), n)
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s, sh=sh: (-8 + 9 * math.sin(TAU * (ph + sh)), 0, s * 5), n)
        a.cycle(side + 'LowerLeg', 'rotation', lambda ph, sh=sh: (20 + 10 * math.sin(TAU * (ph + sh + 0.15)), 0, 0), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph: (-8, 0, 0), n)
    return a


def land():
    a = Anim('land', 0.42, props={'priority': 'movement', 'fade_in': 0.04, 'fade_out': 0.18})
    poses = [
        (0.0, 0.0, 0.0, 0, 0, 0),
        (0.07, -2.6, 0.8, 10, 12, 1),
        (0.16, -2.0, 0.6, 8, 9, 0.8),
        (0.30, -0.6, 0.2, 3, 3, 0.3),
        (0.42, 0.0, 0.0, 0, 0, 0),
    ]
    for t, hy, hz, tilt, chest, k in poses:
        a.pos('LowerTorso', t, (0, hy, hz))
        a.rot('LowerTorso', t, (tilt, 0, 0))
        a.rot('UpperTorso', t, (chest, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            crouch_legs(a, t, side, 0.0, 2.0, hy, hz, tilt, out=2.0 + 2 * k)
            a.rot(side + 'UpperArm', t, (-22 * k - 2, 0, s * (5 + 16 * k)))
            a.rot(side + 'LowerArm', t, (-10 - 22 * k, 0, 0))
    return a


# ---------------------------------------------------------------- вода, лестница, транспорт

def swim():
    T = 1.1
    a = Anim('swim', T, loop=True, props={'priority': 'core', 'fade_in': 0.3, 'look': 0.6})
    arm_x = [(0.0, -180), (0.14, -152), (0.30, -95), (0.44, -25), (0.5, 0), (0.64, 62), (0.80, 118), (1.0, 180)]
    elbow = periodic([(0.0, -6), (0.22, -34), (0.40, -40), (0.52, -14), (0.70, -62), (0.86, -36)])
    out = periodic([(0.0, 4), (0.3, 6), (0.5, 8), (0.72, 34), (0.9, 12)])

    def lin(points, x):
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            if x0 <= x <= x1:
                u = (x - x0) / (x1 - x0)
                u = u * u * (3 - 2 * u)
                return y0 + (y1 - y0) * u
        return points[-1][1]

    n = 20
    for i in range(n + 1):
        ph = i / n
        t = T * ph
        roll = 16 * math.sin(TAU * (ph - 0.1))
        a.rot('LowerTorso', t, (0, roll * 0.6, 0))
        a.rot('UpperTorso', t, (0, roll * 0.5, 0))
        a.rot('Head', t, (0, -roll * 0.8, 0))
        for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
            p = ph + sh
            x = lin(arm_x, p) if p <= 1.0 else lin(arm_x, p - 1.0) + 360
            a.rot(side + 'UpperArm', t, (x, 0, s * out(p % 1)))
            a.rot(side + 'LowerArm', t, (elbow(p % 1), 0, 0))
            a.rot(side + 'Hand', t, (-6, 0, 0))
            k = math.sin(3 * TAU * (ph + sh * 0.33))
            a.rot(side + 'UpperLeg', t, (14 * k + 4, 0, s * 3))
            a.rot(side + 'LowerLeg', t, (14 + 10 * math.sin(3 * TAU * (ph + sh * 0.33) - 0.9), 0, 0))
            a.rot(side + 'Foot', t, (-20 + 8 * k, 0, 0))
    return a


def swim_idle():
    T = 2.0
    a = Anim('swim_idle', T, loop=True, props={'priority': 'core', 'fade_in': 0.3})
    n = 12
    a.cycle('UpperTorso', 'rotation', lambda ph: (5 + 1.5 * math.sin(TAU * ph), 0, 0), n)
    a.cycle('LowerTorso', 'position', lambda ph: (0, 0.5 * math.sin(2 * TAU * ph), 0), n)
    for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s: (
            -38 + 6 * math.sin(2 * TAU * ph), s * (14 * math.sin(2 * TAU * ph)), s * (44 + 8 * math.sin(2 * TAU * ph + 0.6))), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph: (-36 + 8 * math.sin(2 * TAU * ph + 1.0), 0, 0), n)
        a.cycle(side + 'Hand', 'rotation', lambda ph, s=s: (0, 0, s * 18 * math.sin(2 * TAU * ph + 1.3)), n)
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s, sh=sh: (-32 + 18 * math.sin(TAU * (ph + sh)), 0, s * 6), n)
        a.cycle(side + 'LowerLeg', 'rotation', lambda ph, sh=sh: (58 + 26 * math.sin(TAU * (ph + sh) + 1.4), 0, 0), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph, sh=sh: (-14 + 10 * math.sin(TAU * (ph + sh) + 2.0), 0, 0), n)
    return a


def climb():
    T = 1.0
    a = Anim('climb', T, loop=True, props={'priority': 'core', 'fade_in': 0.1, 'look': 0.5})
    arm = periodic([(0.0, -168), (0.18, -150), (0.45, -88), (0.62, -80), (0.82, -128)])
    elb = periodic([(0.0, -8), (0.25, -40), (0.48, -95), (0.65, -90), (0.85, -40)])
    th = periodic([(0.0, -6), (0.2, -10), (0.5, -72), (0.68, -70), (0.85, -32)])
    kn = periodic([(0.0, 8), (0.2, 14), (0.5, 100), (0.68, 96), (0.85, 50)])
    n = 12
    a.cycle('LowerTorso', 'position', lambda ph: (0, 0, -1.0), n)
    a.cycle('UpperTorso', 'rotation', lambda ph: (2, 6 * math.sin(TAU * ph), 0), n)
    for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s, sh=sh: (arm((ph + sh) % 1), 0, s * -5), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph, sh=sh: (elb((ph + sh) % 1), 0, 0), n)
        a.cycle(side + 'Hand', 'rotation', lambda ph: (-10, 0, 0), n)
        # нога своей стороны поднимается, когда своя рука опускается (сдвиг полцикла)
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s, sh=sh: (th((ph + sh) % 1), 0, s * 4), n)
        a.cycle(side + 'LowerLeg', 'rotation', lambda ph, sh=sh: (kn((ph + sh) % 1), 0, 0), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph, sh=sh: (-(th((ph + sh) % 1) * -1 - kn((ph + sh) % 1)) * 0 - 6, 0, 0), n)
    return a


def sit():
    T = 4.0
    a = Anim('sit', T, loop=True, props={'priority': 'core', 'fade_in': 0.3})
    n = 4
    a.cycle('UpperTorso', 'rotation', lambda ph: (4 + 0.8 * math.sin(TAU * ph), 0, 0), n)
    for side, s in (('Right', 1), ('Left', -1)):
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s: (-82, s * 14, s * 4), n)
        a.cycle(side + 'LowerLeg', 'rotation', lambda ph: (24, 0, 0), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph: (-10, 0, 0), n)
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s: (-28 - 1.0 * math.sin(TAU * ph), s * -8, s * 5), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph: (-34, 0, 0), n)
    return a


def glide():
    T = 2.0
    a = Anim('glide', T, loop=True, props={'priority': 'core', 'fade_in': 0.3})
    n = 8
    a.cycle('UpperTorso', 'rotation', lambda ph: (-2, 0, 0), n)
    for side, s, sh in (('Right', 1, 0.0), ('Left', -1, 0.5)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s, sh=sh: (14 + 3 * math.sin(TAU * (ph + sh)), 0, s * (20 + 4 * math.sin(TAU * ph))), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph: (-8, 0, 0), n)
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s, sh=sh: (6 + 3 * math.sin(TAU * (ph + sh)), 0, s * 4), n)
        a.cycle(side + 'LowerLeg', 'rotation', lambda ph, sh=sh: (10 + 4 * math.sin(TAU * (ph + sh + 0.2)), 0, 0), n)
        a.cycle(side + 'Foot', 'rotation', lambda ph: (-22, 0, 0), n)
    return a


def sleep():
    a = Anim('sleep', 4.0, loop=True, props={'priority': 'core', 'fade_in': 0.5, 'look': 0})
    n = 4
    a.cycle('UpperTorso', 'rotation', lambda ph: (0.8 * math.sin(TAU * ph), 0, 0), n)
    a.cycle('Head', 'rotation', lambda ph: (2, 0, 6), n)
    for side, s in (('Right', 1), ('Left', -1)):
        a.cycle(side + 'UpperArm', 'rotation', lambda ph, s=s: (-4, 0, s * (6 + 0.8 * math.sin(TAU * ph))), n)
        a.cycle(side + 'LowerArm', 'rotation', lambda ph: (-12, 0, 0), n)
        a.cycle(side + 'UpperLeg', 'rotation', lambda ph, s=s: (0, 0, s * 2), n)
    return a


# ---------------------------------------------------------------- движения мода

def charge():
    """Присед перед заряженным прыжком (время ведёт скрипт: сколько держится присед)."""
    a = Anim('charge', 1.2, loop='hold', props={'priority': 'movement'})
    for t, k in ((0.0, 0.0), (0.25, 0.55), (0.75, 1.0), (1.2, 1.0)):
        hy, hz, tilt, chest = -4.2 * k, 1.6 * k, 20 * k, 18 * k
        a.pos('LowerTorso', t, (0, hy, hz))
        a.rot('LowerTorso', t, (tilt, 0, 0))
        a.rot('UpperTorso', t, (chest, 0, 0))
        a.rot('Head', t, (-8 * k, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            crouch_legs(a, t, side, 0.6 * k * s * 0 + 0.0, 2.0, hy, hz, tilt, out=2 + 4 * k)
            a.rot(side + 'UpperArm', t, (38 * k - 2, s * 6 * k, s * (6 + 10 * k)))
            a.rot(side + 'LowerArm', t, (-10 - 8 * k, 0, 0))
            a.rot(side + 'Hand', t, (14 * k, 0, 0))
    # лёгкая дрожь в конце — набранная сила
    for i, t in enumerate((0.85, 0.95, 1.05, 1.15)):
        a.rot('UpperTorso', t, (18 + (0.6 if i % 2 else -0.6), 0, 0))
    return a


def leap():
    a = Anim('leap', 1.2, loop='hold', props={'priority': 'movement'})
    keys = [
        # t, таз y, наклон таза, грудь, руки x, руки z, локти, бедро П, колено П, бедро Л, колено Л
        (0.0, -3.0, 14, 14, 30, 14, -14, -14, 70, -14, 70),
        # у поднятой руки Z < 0 разводит её наружу (у опущенной — наоборот)
        (0.12, 1.0, -4, -8, -166, -10, -6, 12, 4, 8, 6),
        (0.30, 1.2, -2, -4, -150, -22, -12, 6, 10, 2, 12),
        (0.55, 0.6, 6, 6, -40, 48, -30, -62, 96, -48, 86),
        (0.85, 0.2, 2, 2, -18, 30, -24, -24, 40, -8, 22),
        (1.2, 0.0, 0, 0, -12, 18, -20, -14, 26, -4, 14),
    ]
    for t, hy, tilt, chest, ax, az, el, rt, rk, lt, lk in keys:
        a.pos('LowerTorso', t, (0, hy, 0))
        a.rot('LowerTorso', t, (tilt, 0, 0))
        a.rot('UpperTorso', t, (chest, 0, 0))
        a.rot('RightUpperArm', t, (ax, -4, az))
        a.rot('LeftUpperArm', t, (ax, 4, -az))
        a.rot('RightLowerArm', t, (el, 0, 0))
        a.rot('LeftLowerArm', t, (el, 0, 0))
        a.rot('RightUpperLeg', t, (rt, 0, 3))
        a.rot('RightLowerLeg', t, (rk, 0, 0))
        a.rot('LeftUpperLeg', t, (lt, 0, -3))
        a.rot('LeftLowerLeg', t, (lk, 0, 0))
        a.rot('RightFoot', t, (-(-rt - rk) * 0.4 - 10, 0, 0))
        a.rot('LeftFoot', t, (-(-lt - lk) * 0.4 - 10, 0, 0))
    return a


def pockets(a, t, k=1.0, lift=0.0):
    """Руки в карманах (поза Годжо в полёте)."""
    for side, s in (('Right', 1), ('Left', -1)):
        a.rot(side + 'UpperArm', t, (14 * k + lift, s * -10 * k, s * 9 * k))
        a.rot(side + 'LowerArm', t, (-48 * k, s * 8 * k, 0))
        a.rot(side + 'Hand', t, (22 * k, 0, s * -6 * k))


def fly_hover():
    T = 2.4
    a = Anim('fly_hover', T, loop=True, props={'priority': 'movement', 'fade_in': 0.3})
    n = 8
    for i in range(n + 1):
        ph = i / n
        t = T * ph
        b = math.sin(TAU * ph)
        a.pos('LowerTorso', t, (0, 0.7 * b, 0))
        a.rot('LowerTorso', t, (2, 0, 0))
        a.rot('UpperTorso', t, (1 - 0.8 * b, 0, 0))
        a.rot('Head', t, (-1, 0, 0))
        pockets(a, t, 1.0, 1.5 * b)
        lag = math.sin(TAU * (ph - 0.15))
        a.rot('RightUpperLeg', t, (-14 + 3 * lag, 0, 3))
        a.rot('RightLowerLeg', t, (30 + 5 * lag, 0, 0))
        a.rot('RightFoot', t, (-14, 0, 0))
        a.rot('LeftUpperLeg', t, (4 + 2 * lag, 0, -3))
        a.rot('LeftLowerLeg', t, (14 + 4 * lag, 0, 0))
        a.rot('LeftFoot', t, (-20, 0, 0))
    return a


def fly_glide():
    T = 2.0
    a = Anim('fly_glide', T, loop=True, props={'priority': 'movement', 'fade_in': 0.3})
    n = 8
    for i in range(n + 1):
        ph = i / n
        t = T * ph
        w = math.sin(TAU * ph)
        a.rot('UpperTorso', t, (-4, 0, 0))
        a.rot('Head', t, (-6, 0, 0))
        pockets(a, t, 0.8, 10 + 2 * w)
        a.rot('RightUpperLeg', t, (14 + 3 * w, 0, 2))
        a.rot('RightLowerLeg', t, (22 + 4 * w, 0, 0))
        a.rot('RightFoot', t, (-26, 0, 0))
        a.rot('LeftUpperLeg', t, (20 - 3 * w, 0, -2))
        a.rot('LeftLowerLeg', t, (34 - 4 * w, 0, 0))
        a.rot('LeftFoot', t, (-28, 0, 0))
    return a


def fly_boost():
    T = 0.6
    a = Anim('fly_boost', T, loop=True, props={'priority': 'movement', 'fade_in': 0.2})
    n = 6
    for i in range(n + 1):
        ph = i / n
        t = T * ph
        w = math.sin(TAU * ph)
        a.rot('UpperTorso', t, (-6, 0, 0))
        a.rot('Head', t, (-10, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            a.rot(side + 'UpperArm', t, (42 + 2 * w * s, s * -6, s * 9))
            a.rot(side + 'LowerArm', t, (-10, 0, 0))
            a.rot(side + 'Hand', t, (10, 0, 0))
            a.rot(side + 'UpperLeg', t, (16 + 2 * w * s, 0, s * 2))
            a.rot(side + 'LowerLeg', t, (10 + 3 * w * s, 0, 0))
            a.rot(side + 'Foot', t, (-30, 0, 0))
    return a


def fly_land():
    a = Anim('fly_land', 1.0, loop='hold', props={'priority': 'movement', 'fade_in': 0.25})
    for t, k in ((0.0, 0.0), (0.35, 0.8), (1.0, 1.0)):
        a.rot('UpperTorso', t, (4 * k, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            a.rot(side + 'UpperArm', t, (-22 * k + 12 * (1 - k), 0, s * (8 + 30 * k)))
            a.rot(side + 'LowerArm', t, (-20 * k - 30 * (1 - k), 0, 0))
            a.rot(side + 'UpperLeg', t, (-6 * k * s * 0 - 4 * k, 0, s * (2 + 3 * k)))
            a.rot(side + 'LowerLeg', t, (12 * k + 24 * (1 - k), 0, 0))
            a.rot(side + 'Foot', t, (-6 * k - 18 * (1 - k), 0, 0))
    return a


def dash_front():
    a = Anim('dash_front', 0.7, props={'priority': 'movement', 'fade_in': 0.06, 'fade_out': 0.2})
    keys = [(0.0, 0.0), (0.08, 1.0), (0.42, 0.92), (0.58, 0.55), (0.7, 0.3)]
    for t, k in keys:
        hy, hz = -3.0 * k, 0.8 * k
        a.pos('LowerTorso', t, (0, hy, hz))
        a.rot('LowerTorso', t, (22 * k, 0, 0))
        a.rot('UpperTorso', t, (18 * k, -6 * k, 0))
        a.rot('Head', t, (-18 * k, 0, 0))
        a.rot('RightUpperLeg', t, (-(52 * k) - 22 * k, 0, 3))
        a.rot('RightLowerLeg', t, (66 * k, 0, 0))
        a.rot('RightFoot', t, (-(52 * k - 66 * k) * 0 + 4 * k, 0, 0))
        a.rot('LeftUpperLeg', t, ((34 * k) - 22 * k, 0, -3))
        a.rot('LeftLowerLeg', t, (28 * k, 0, 0))
        a.rot('LeftFoot', t, (-30 * k, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            a.rot(side + 'UpperArm', t, (58 * k, s * -8 * k, s * (6 + 12 * k)))
            a.rot(side + 'LowerArm', t, (-8 * k, 0, 0))
            a.rot(side + 'Hand', t, (18 * k, 0, 0))
    return a


def dash_back():
    a = Anim('dash_back', 0.7, props={'priority': 'movement', 'fade_in': 0.06, 'fade_out': 0.2})
    keys = [(0.0, 0.0), (0.08, 1.0), (0.42, 0.9), (0.58, 0.5), (0.7, 0.25)]
    for t, k in keys:
        a.pos('LowerTorso', t, (0, -1.8 * k, -0.6 * k))
        a.rot('LowerTorso', t, (-6 * k, 0, 0))
        a.rot('UpperTorso', t, (-10 * k, 0, 0))
        a.rot('Head', t, (12 * k, 0, 0))
        a.rot('RightUpperLeg', t, (-36 * k + 6 * k, 0, 4 * k))
        a.rot('RightLowerLeg', t, (24 * k, 0, 0))
        a.rot('RightFoot', t, (8 * k, 0, 0))
        a.rot('LeftUpperLeg', t, (24 * k + 6 * k, 0, -4 * k))
        a.rot('LeftLowerLeg', t, (36 * k, 0, 0))
        a.rot('LeftFoot', t, (-22 * k, 0, 0))
        for side, s in (('Right', 1), ('Left', -1)):
            a.rot(side + 'UpperArm', t, (-48 * k, s * 10 * k, s * (8 + 18 * k)))
            a.rot(side + 'LowerArm', t, (-26 * k, 0, 0))
            a.rot(side + 'Hand', t, (-10 * k, 0, 0))
    return a


def dash_side(name, d):
    """d = +1 — влево, −1 — вправо (рисуем влево, вправо — зеркально по знакам)."""
    a = Anim(name, 0.3, props={'priority': 'movement', 'fade_in': 0.04, 'fade_out': 0.16})
    keys = [(0.0, 0.0), (0.05, 1.0), (0.2, 0.85), (0.3, 0.45)]
    for t, k in keys:
        a.pos('LowerTorso', t, (0, -1.8 * k, 0))
        a.rot('LowerTorso', t, (6 * k, 0, d * 6 * k))
        a.rot('UpperTorso', t, (6 * k, d * 8 * k, d * 10 * k))
        a.rot('Head', t, (0, 0, -d * 12 * k))
        # ведущая нога в сторону движения, толчковая — от неё
        lead, push = ('Left', 'Right') if d > 0 else ('Right', 'Left')
        sl = 1 if lead == 'Right' else -1
        a.rot(lead + 'UpperLeg', t, (-10 * k, 0, sl * 26 * k - d * 6 * k))
        a.rot(lead + 'LowerLeg', t, (22 * k, 0, 0))
        a.rot(lead + 'Foot', t, (-4 * k, 0, -sl * 14 * k))
        sp = 1 if push == 'Right' else -1
        a.rot(push + 'UpperLeg', t, (6 * k, 0, sp * 10 * k - d * 6 * k))
        a.rot(push + 'LowerLeg', t, (14 * k, 0, 0))
        a.rot(push + 'Foot', t, (-10 * k, 0, 0))
        a.rot(lead + 'UpperArm', t, (-20 * k, 0, sl * 22 * k))
        a.rot(lead + 'LowerArm', t, (-30 * k, 0, 0))
        a.rot(push + 'UpperArm', t, (-12 * k, 0, sp * 48 * k))
        a.rot(push + 'LowerArm', t, (-14 * k, 0, 0))
    return a


# ---------------------------------------------------------------- руки и предметы

def hold_item():
    a = Anim('hold_item', 2.0, loop=True, props={'priority': 'idle', 'fade_in': 0.15, 'fp': 'none'})
    for i in range(5):
        t = 0.5 * i
        w = math.sin(TAU * i / 4)
        a.rot('RightUpperArm', t, (-15 - 1.0 * w, -4, 2))
        a.rot('RightLowerArm', t, (-20 - 1.0 * w, 0, 0))
        a.rot('RightHand', t, (-4, 0, 0))
    return a


def swing():
    a = Anim('swing', 0.32, props={'priority': 'action', 'fade_in': 0.03, 'fade_out': 0.12, 'fp': 'none', 'look': 0.6})
    keys = [
        (0.0, (-40, -6, 6), (-20, 0, 0), (0, 0, 0)),
        (0.07, (-128, -12, 14), (-34, 0, 0), (0, 12, 0)),
        (0.17, (-46, 18, -14), (-8, 0, 0), (2, -14, 0)),
        (0.32, (-22, 0, 2), (-14, 0, 0), (0, -2, 0)),
    ]
    for t, ua, la, ut in keys:
        a.rot('RightUpperArm', t, ua)
        a.rot('RightLowerArm', t, la)
        a.rot('UpperTorso', t, ut)
    a.marker(0.12, 'hit')
    return a


def use_pose(name, right_upper, right_lower, hand=(0, 0, 0), left_upper=None, left_lower=None, extra=None,
             loop_amp=0.0, period=0.5, props=None):
    p = {'priority': 'action', 'fade_in': 0.12, 'fade_out': 0.15, 'fp': 'none'}
    p.update(props or {})
    a = Anim(name, period, loop=True, props=p)
    for i in range(5):
        t = period * i / 4
        w = math.sin(TAU * i / 4) * loop_amp
        a.rot('RightUpperArm', t, (right_upper[0] + w, right_upper[1], right_upper[2]))
        a.rot('RightLowerArm', t, (right_lower[0] - w * 0.5, right_lower[1], right_lower[2]))
        a.rot('RightHand', t, hand)
        if left_upper is not None:
            a.rot('LeftUpperArm', t, left_upper)
            a.rot('LeftLowerArm', t, left_lower or (0, 0, 0))
        for bone, v in (extra or {}).items():
            a.rot(bone, t, v)
    return a


def items():
    return [
        hold_item(),
        swing(),
        use_pose('use_eat', (-84, -36, 4), (-58, 0, 0), (-12, 0, 0), extra={'Head': (6, 0, 0)}, loop_amp=6, period=0.4,
                 props={'look': 0.7}),
        use_pose('use_drink', (-104, -26, 4), (-56, 0, 0), (-18, 0, 0), extra={'Head': (-14, 0, 0)}, loop_amp=2,
                 period=0.8, props={'look': 0.4}),
        use_pose('use_block', (-48, -32, 0), (-18, 0, 0), props={'aim': 0.5}),
        use_pose('use_bow', (-90, -6, 0), (0, 0, 0), left_upper=(-88, 26, 0), left_lower=(-22, 0, -30),
                 extra={'UpperTorso': (0, 8, 0)}, props={'aim': 1, 'look': 1}),
        use_pose('use_spear', (-166, 0, -4), (-8, 0, 0), extra={'UpperTorso': (0, 14, 0)}, props={'aim': 0.5}),
        use_pose('use_crossbow', (-56, -44, 0), (-14, 0, 0), left_upper=(-74, 40, 0), left_lower=(-40, 0, 0),
                 props={'aim': 0.6}),
        use_pose('hold_crossbow', (-86, -17, 0), (0, 0, 0), left_upper=(-86, 34, 0), left_lower=(-10, 0, 0),
                 props={'aim': 1, 'look': 1}, period=2.0),
        use_pose('use_spyglass', (-112, -16, 0), (-70, 0, 0), (-10, 0, 0), props={'aim': 1, 'look': 1}),
        use_pose('use_horn', (-82, -32, 0), (-70, 0, 0), (-10, 0, 0), props={'aim': 1, 'look': 1}),
        use_pose('use_brush', (-36, 0, 0), (-26, 0, 0), extra={'RightHand': (0, 0, 0)}, loop_amp=8, period=0.5),
    ]


def all_anims():
    return [idle(), idle('idle_look', True), walk(), run(), sprint(), crouch_idle(), crouch_walk(),
            jump(), fall(), land(), swim(), swim_idle(), climb(), sit(), glide(), sleep(),
            charge(), leap(), fly_hover(), fly_glide(), fly_boost(), fly_land(),
            dash_front(), dash_back(), dash_side('dash_left', 1), dash_side('dash_right', -1)] + items()


if __name__ == '__main__':
    write('locomotion.animation.json', all_anims())
