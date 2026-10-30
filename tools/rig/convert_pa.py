"""Перевод анимаций техник из формата Player Animator (Emotecraft v3) в Blockbench-анимации модели R15.

  python3 tools/rig/convert_pa.py            — все техники → animations/player/techniques.animation.json

Исходники — tools/rig/legacy_pa/*.json (их пишут tools/gen_red_anim.py, gen_lapse_blue_anim.py, gen_domain_anim.py,
gen_m1_assets.py, gen_simple_domain_assets.py). Готовую анимацию можно дальше править прямо в Blockbench.
Максимальный Фиолетовый не переводится: он остаётся на Player Animator.

Тайминги сохраняются до тика (1 тик = 0,05 с): эффекты техник привязаны к тикам в коде.
Как Player Animator ставил позу (декомпилированный 1.0.2-rc1, повторено здесь):
  - голова, руки, ноги — повороты в осях модели (руки не следуют за корпусом), порядок Z·Y·X;
  - torso — поворот корпуса и сгиб (bendy-lib) верхней половины корпуса вокруг его середины;
  - сгиб руки/ноги — поворот нижней половины вокруг оси X (рука: < 0 — сгиб в локте, нога: > 0 — колено);
  - body — сдвиг и поворот всей модели вокруг точки 0,7 блока над ногами (в мировых осях до разворота модели);
  - сглаживание ключа действует на отрезок ПОСЛЕ него (easeBeforeKeyframe = false).
В R15: body → LowerTorso, torso + сгиб → UpperTorso (у пояса), руки и голова пересчитываются в оси
UpperTorso так, чтобы в мире смотреть туда же, куда в Player Animator; сгибы → LowerArm/LowerLeg,
стопа держится параллельно земле.
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anim_dsl import Anim, write, ROOT  # noqa: E402

# исходники (генераторы tools/gen_*_anim.py пишут сюда); в мод не входят
PA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'legacy_pa')
MODEL_SCALE = 0.9375
PX_PER_BLOCK = 16.0 / MODEL_SCALE

# ------------------------------------------------------------------------------------- сглаживания PA


def _io(f):
    return lambda x: f(2 * x) / 2 if x < 0.5 else 1 - f(2 * (1 - x)) / 2


_BASE = {
    'SINE': lambda x: 1 - math.cos(x * math.pi / 2),
    'QUAD': lambda x: x * x,
    'CUBIC': lambda x: x ** 3,
    'QUART': lambda x: x ** 4,
    'QUINT': lambda x: x ** 5,
}
PA_EASE = {'LINEAR': lambda x: x, 'CONSTANT': lambda x: 0.0}
for _k, _f in _BASE.items():
    PA_EASE['IN' + _k] = _f
    PA_EASE['OUT' + _k] = (lambda f: lambda x: 1 - f(1 - x))(_f)
    PA_EASE['INOUT' + _k] = _io(_f)

# то же сглаживание в GeckoLib (ключ, К которому идёт отрезок)
GL_EASE = {'LINEAR': 'linear', 'CONSTANT': 'step'}
for _k in _BASE:
    GL_EASE['IN' + _k] = 'easeIn' + _k.capitalize()
    GL_EASE['OUT' + _k] = 'easeOut' + _k.capitalize()
    GL_EASE['INOUT' + _k] = 'easeInOut' + _k.capitalize()


def ease_name(s):
    s = (s or 'linear').upper()
    if s == 'STEP':
        return 'CONSTANT'
    if s.startswith('EASE'):
        s = s[4:]
    return s if s in PA_EASE else 'LINEAR'


# ------------------------------------------------------------------------------------- чтение PA

PARTS = ('head', 'torso', 'rightArm', 'leftArm', 'rightLeg', 'leftLeg', 'body')
CHANNELS = ('x', 'y', 'z', 'pitch', 'yaw', 'roll', 'bend', 'axis')


class PAChannel:
    def __init__(self):
        self.keys = []  # (тик, значение, сглаживание)

    def add(self, tick, value, ease):
        self.keys = [k for k in self.keys if k[0] != tick] + [(tick, value, ease)]
        self.keys.sort(key=lambda k: k[0])


class PAAnim:
    def __init__(self, path):
        d = json.load(open(path, encoding='utf-8'))
        e = d['emote']
        self.name = os.path.splitext(os.path.basename(path))[0]
        self.begin = e.get('beginTick', 0)
        self.end = e['endTick']
        self.stop = e.get('stopTick', self.end)
        self.loop = bool(e.get('isLoop', False))
        self.ret = e.get('returnTick', 0)
        self.parts = {}
        ticks = set()
        for m in e['moves']:
            t = m['tick']
            ease = ease_name(m.get('easing'))
            ticks.add(t)
            for part, vals in m.items():
                if not isinstance(vals, dict):
                    continue
                chans = self.parts.setdefault(part, {c: PAChannel() for c in CHANNELS})
                for c, v in vals.items():
                    if c in chans:
                        chans[c].add(t, float(v), ease)
        self.ticks = sorted(ticks)

    def has(self, part):
        return part in self.parts

    def value(self, part, chan, t):
        """Значение канала (градусы/блоки) в тик t — как KeyframeAnimationPlayer.Axis."""
        keys = self.parts[part][chan].keys
        if not keys:
            return 0.0
        if t <= keys[0][0]:
            return keys[0][1]
        for i in range(len(keys) - 1):
            a, b = keys[i], keys[i + 1]
            if a[0] <= t <= b[0]:
                f = (t - a[0]) / (b[0] - a[0])
                return a[1] + (b[1] - a[1]) * PA_EASE[a[2]](f)
        last = keys[-1]
        if self.loop:
            period_end = keys[0][0] + self.end - self.ret + 1
            if t <= period_end:
                f = (t - last[0]) / max(1e-9, period_end - last[0])
                return last[1] + (keys[0][1] - last[1]) * PA_EASE[last[2]](f)
        return last[1]

    def ease_after(self, part, chan, t):
        for k in self.parts[part][chan].keys:
            if k[0] == t:
                return k[2]
        return None


# ------------------------------------------------------------------------------------- повороты

def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def zyx(x, y, z):
    """Поворот как у ModelPart / кости Blockbench (градусы): Rz·Ry·Rx."""
    return rz(math.radians(z)) @ ry(math.radians(y)) @ rx(math.radians(x))


def euler(m, prev=None):
    """Углы Z·Y·X (градусы); из двух решений и сдвигов на 360° — ближайшее к prev."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    y = math.asin(sy)
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    else:  # вырожденный случай: Z не определён
        x = math.atan2(-m[1][2], m[1][1])
        z = 0.0
    sols = [(x, y, z), (x + math.pi, math.pi - y, z + math.pi)]
    sols = [tuple(math.degrees(v) for v in s) for s in sols]
    if prev is None:
        return min(sols, key=lambda s: abs(s[0]) + abs(s[1]) + abs(s[2]))

    def unwrap(v, p):
        return v + 360.0 * round((p - v) / 360.0)

    best = None
    for s in sols:
        s = tuple(unwrap(v, p) for v, p in zip(s, prev))
        d = sum((v - p) ** 2 for v, p in zip(s, prev))
        if best is None or d < best[0]:
            best = (d, s)
    return best[1]


# ------------------------------------------------------------------------------------- поза R15

ARM = {'rightArm': 'Right', 'leftArm': 'Left'}
LEG = {'rightLeg': 'Right', 'leftLeg': 'Left'}


def r15_pose(pa, t):
    """{кость: {'rotation': R (матрица) или (x,y,z), 'position': (x,y,z)}} в момент t (тики)."""
    v = lambda part, ch: pa.value(part, ch, t)  # noqa: E731
    out = {}
    if pa.has('body'):
        # поворот вокруг точки ≈ таза; мировые оси до разворота модели → оси модели: X и Y меняют знак
        out['LowerTorso'] = {
            'rotation': zyx(-v('body', 'pitch'), -v('body', 'yaw'), v('body', 'roll')),
            'position': (-v('body', 'x') * PX_PER_BLOCK, v('body', 'y') * PX_PER_BLOCK, v('body', 'z') * PX_PER_BLOCK),
        }
    ut = np.eye(3)
    if pa.has('torso'):
        ut = zyx(v('torso', 'pitch'), v('torso', 'yaw'), v('torso', 'roll')) @ rx(math.radians(v('torso', 'bend')))
        out['UpperTorso'] = {'rotation': ut}
    ut_inv = ut.T
    if pa.has('head'):
        out['Head'] = {'rotation': ut_inv @ zyx(v('head', 'pitch'), v('head', 'yaw'), v('head', 'roll'))}
    for part, side in ARM.items():
        if not pa.has(part):
            continue
        out[side + 'UpperArm'] = {'rotation': ut_inv @ zyx(v(part, 'pitch'), v(part, 'yaw'), v(part, 'roll'))}
        out[side + 'LowerArm'] = {'rotation': rx(math.radians(v(part, 'bend')))}
        out[side + 'Hand'] = {'rotation': np.eye(3)}
    lt_x = -v('body', 'pitch') if pa.has('body') else 0.0
    for part, side in LEG.items():
        if not pa.has(part):
            continue
        leg = zyx(v(part, 'pitch'), v(part, 'yaw'), v(part, 'roll'))
        knee = v(part, 'bend')
        out[side + 'UpperLeg'] = {'rotation': leg}
        out[side + 'LowerLeg'] = {'rotation': rx(math.radians(knee))}
        # стопа параллельна земле: гасим наклон голени (бедро + колено + таз), не больше 45°
        foot = -(lt_x + v(part, 'pitch') + knee)
        out[side + 'Foot'] = {'rotation': rx(math.radians(max(-45.0, min(45.0, foot))))}
    return out


def to_values(pose, prev):
    vals = {}
    for bone, chans in pose.items():
        for ch, val in chans.items():
            if ch == 'rotation':
                p = prev.get((bone, ch))
                vals[(bone, ch)] = euler(val, p)
            else:
                vals[(bone, ch)] = tuple(val)
    return vals


# ------------------------------------------------------------------------------------- сборка клипа

def segment_ease(pa, t):
    """Сглаживание отрезка, начинающегося в тик t: общее у всех каналов с ключом в t."""
    eases = set()
    for part, chans in pa.parts.items():
        for c in chans.values():
            for k in c.keys:
                if k[0] == t:
                    eases.add(k[2])
    return eases.pop() if len(eases) == 1 else None


def sample_gl(a_val, b_val, ease, f):
    if ease == 'CONSTANT':
        return a_val
    w = PA_EASE[ease](f)
    return tuple(x + (y - x) * w for x, y in zip(a_val, b_val))


def convert(pa, props=None, markers=(), name=None):
    length_ticks = pa.end - pa.ret + 1 if pa.loop else pa.end
    loop = True if pa.loop else False
    a = Anim(name or pa.name, length_ticks / 20.0, loop=loop, props=props or {})
    key_ticks = [t for t in pa.ticks if t <= length_ticks]
    if key_ticks[0] != 0:
        key_ticks.insert(0, 0)
    if key_ticks[-1] < length_ticks:
        key_ticks.append(length_ticks)

    # значения в ключевые тики (+ промежуточные, где отрезок нельзя передать одним сглаживанием)
    prev = {}
    samples = []  # (тик, значения, сглаживание отрезка, ведущего к этому ключу)
    for i, t in enumerate(key_ticks):
        vals = to_values(r15_pose(pa, t), prev)
        if i == 0:
            samples.append((t, vals, None))
            prev = vals
            continue
        t0 = key_ticks[i - 1]
        ease = segment_ease(pa, t0) or 'LINEAR'
        # проверка: даёт ли одно сглаживание тот же путь, что PA (поворот корпуса делает путь рук нелинейным)
        ok = segment_ease(pa, t0) is not None
        if ok:
            p0 = samples[-1][1]
            for f in (0.25, 0.5, 0.75):
                tt = t0 + (t - t0) * f
                exact = to_values(r15_pose(pa, tt), p0)
                for k, ev in exact.items():
                    gv = sample_gl(p0[k], vals[k], ease, f)
                    if max(abs(x - y) for x, y in zip(ev, gv)) > (1.5 if k[1] == 'rotation' else 0.15):
                        ok = False
                        break
                if not ok:
                    break
        if ok:
            samples.append((t, vals, ease))
        else:
            # по тикам (и полутикам, если отрезок короткий), линейно
            step = 0.5 if t - t0 <= 4 else 1.0
            tt = t0 + step
            while tt < t - 1e-9:
                v = to_values(r15_pose(pa, tt), samples[-1][1])
                samples.append((tt, v, 'LINEAR'))
                tt += step
            samples.append((t, to_values(r15_pose(pa, t), samples[-1][1]), 'LINEAR'))
        prev = samples[-1][1]

    if pa.loop:
        # последний ключ цикла = первый (тот же путь, что PA проходит к началу цикла)
        first = samples[0][1]
        last_t = samples[-1][0]
        if last_t < length_ticks:
            samples.append((length_ticks, {k: v for k, v in first.items()}, segment_ease(pa, last_t) or 'LINEAR'))

    for t, vals, ease in samples:
        gl = None if ease is None else GL_EASE[ease]
        for (bone, ch), v in vals.items():
            a.key(bone, ch, t / 20.0, v, gl if gl != 'linear' else None)
    for t, text in markers:
        a.marker(t / 20.0, text)
    return a


# ------------------------------------------------------------------------------------- старый формат jn_anim

JN_EASE = {'out': 'easeOutQuad', 'in': 'easeInQuad', 'smooth': 'catmullrom', 'linear': None}


def convert_jn(name, data, props):
    """Клипы старого движка (blue_charge, max_blue): руки в осях модели [x, y, z, локоть], корпус [x, y, z]."""
    length = data['length'] / 20.0
    a = Anim(name, length, loop='hold' if data.get('hold_last') else False, props=props)
    prev = {}
    for k in data['keys']:
        t = k['t'] / 20.0
        ease = JN_EASE.get(k.get('ease', 'smooth'), 'catmullrom')
        pose = k['pose']
        ut = zyx(*pose.get('torso', (0, 0, 0)))
        a.key('UpperTorso', 'rotation', t, euler(ut, prev.get('UpperTorso')), ease)
        prev['UpperTorso'] = euler(ut, prev.get('UpperTorso'))
        for part, side in ARM.items():
            if part not in pose:
                continue
            x, y, z, elbow = pose[part]
            arm = euler(ut.T @ zyx(x, y, z), prev.get(side))
            prev[side] = arm
            a.key(side + 'UpperArm', 'rotation', t, arm, ease)
            a.key(side + 'LowerArm', 'rotation', t, (elbow, 0, 0), ease)
            a.key(side + 'Hand', 'rotation', t, (0, 0, 0), ease)
    return a


# ------------------------------------------------------------------------------------- набор техник

def fade_out(pa):
    return round(max(0.05, (pa.stop - pa.end) / 20.0), 3)


TECHNIQUES = [
    # имя, свойства, маркеры (тик, текст) — тики из кода техник
    ('red_cast', {}, [(13, 'fire')]),
    ('max_red', {'look': 0.5}, [(5, 'aura'), (22, 'shockwave'), (26, 'fire')]),
    ('lapse_blue', {}, []),
    ('lapse_blue_grab_low', {}, []),
    ('lapse_blue_grab_mid', {}, []),
    ('lapse_blue_grab_high', {}, []),
    ('lapse_blue_hold', {'priority': 'action'}, []),
    ('lapse_blue_throw', {}, []),
    ('domain_cast', {'look': 0.2}, []),
    ('simple_domain', {}, []),
    ('m1_jab_l', {'priority': 'action2'}, []),
    ('m1_jab_r', {'priority': 'action2'}, []),
    ('m1_final', {'priority': 'action2'}, []),
    ('m1_knockdown', {'priority': 'action3'}, []),
]

OLD_JN = {
    # зарядка Синего и Максимальный Синий (были в старом движке; позы перенесены как есть)
    'blue_charge': {
        'length': 20, 'hold_last': True,
        'keys': [
            {'t': 0, 'pose': {'torso': [1, -1, 0], 'rightArm': [-14, -4, 6, -12], 'leftArm': [-8, 4, -6, -12]}},
            {'t': 6, 'ease': 'out', 'pose': {'torso': [5, -7, -1], 'rightArm': [-70, -26, 4, -62], 'leftArm': [-36, 26, -12, -58]}},
            {'t': 12, 'pose': {'torso': [7, -9, -2], 'rightArm': [-80, -30, 2, -74], 'leftArm': [-40, 30, -10, -70]}},
            {'t': 20, 'pose': {'torso': [8, -10, -2], 'rightArm': [-83, -31, 1, -78], 'leftArm': [-42, 31, -10, -72]}},
        ]},
    'max_blue': {
        'length': 40,
        'keys': [
            {'t': 0, 'pose': {'torso': [8, -10, -2], 'rightArm': [-83, -31, 1, -78], 'leftArm': [-42, 31, -10, -72]}},
            {'t': 5, 'ease': 'in', 'pose': {'torso': [10, -8, -2], 'rightArm': [-78, -34, 2, -88], 'leftArm': [-44, 33, -11, -78]}},
            {'t': 11, 'ease': 'out', 'pose': {'torso': [2, -16, -4], 'rightArm': [-92, -12, 2, -10], 'leftArm': [-48, 36, -14, -58]}},
            {'t': 20, 'pose': {'torso': [1, -18, -5], 'rightArm': [-90, -10, 0, -6], 'leftArm': [-50, 37, -15, -56]}},
            {'t': 26, 'pose': {'torso': [1, -18, -5], 'rightArm': [-93, -10, 2, -4], 'leftArm': [-50, 37, -14, -55]}},
            {'t': 32, 'pose': {'torso': [1, -17, -5], 'rightArm': [-89, -11, -1, -7], 'leftArm': [-50, 37, -16, -57]}},
            {'t': 40, 'ease': 'smooth', 'pose': {'torso': [2, -12, -4], 'rightArm': [-76, -14, 2, -16], 'leftArm': [-46, 33, -14, -52]}},
        ]},
}


def all_techniques():
    from gen_techniques import MADE_HERE  # эти клипы теперь нарисованы позами заново (gojo.animation.json)
    out = []
    for name, props, markers in TECHNIQUES:
        if name in MADE_HERE:
            continue
        pa = PAAnim(os.path.join(PA_DIR, name + '.json'))
        p = {'priority': 'action', 'fade_out': fade_out(pa)}
        p.update(props)
        out.append(convert(pa, p, markers))
    if 'blue_charge' not in MADE_HERE:
        out.append(convert_jn('blue_charge', OLD_JN['blue_charge'],
                              {'priority': 'action', 'fade_in': 0.15, 'fade_out': 0.25, 'look': 1}))
    if 'max_blue' not in MADE_HERE:
        out.append(convert_jn('max_blue', OLD_JN['max_blue'],
                              {'priority': 'action', 'fade_in': 0.15, 'fade_out': 0.4, 'look': 1}))
    return out


if __name__ == '__main__':
    write('techniques.animation.json', all_techniques())
