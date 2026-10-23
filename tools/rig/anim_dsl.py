"""Сборка анимаций для Blockbench (формат Bedrock / GeckoLib) из Python.

Значения — как в Blockbench: повороты в градусах, смещения в пикселях. Для модели R15 (tools/rig/rig.py):
  поворот X < 0 — конечность вперёд/вверх, X > 0 у корпуса — наклон вперёд, у головы — взгляд вниз,
  у голени — сгиб колена, у предплечья X < 0 — сгиб локтя;
  Z > 0 — правая рука/нога наружу (левая — внутрь), у корпуса — наклон влево;
  Y > 0 — поворот вправо.
  Смещение: X > 0 — влево, Y > 0 — вверх, Z > 0 — назад.

  a = Anim('walk', 0.5, loop=True, props={'priority': 'core', 'move_speed': 4.3})
  a.rot('RightUpperArm', 0.0, (-30, 0, 5))
  a.cycle_rot('RightUpperLeg', fn, samples=12)   # периодическая кривая fn(фаза 0..1) -> (x, y, z)
  write('locomotion.animation.json', [a, ...])
"""
import json
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ANIM_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'jujutsu_neon', 'animations', 'player')

R15 = ['LowerTorso', 'UpperTorso', 'Head',
       'RightUpperArm', 'RightLowerArm', 'RightHand', 'LeftUpperArm', 'LeftLowerArm', 'LeftHand',
       'RightUpperLeg', 'RightLowerLeg', 'RightFoot', 'LeftUpperLeg', 'LeftLowerLeg', 'LeftFoot']


def _r(v):
    v = round(float(v), 3)
    return 0.0 if v == 0 else v


class Anim:
    def __init__(self, name, length, loop=False, props=None):
        self.name = name
        self.length = float(length)
        self.loop = loop
        self.props = dict(props or {})
        self.bones = {}
        self.markers = []
        self.sounds = []

    def _ch(self, bone, ch):
        return self.bones.setdefault(bone, {}).setdefault(ch, {})

    def key(self, bone, ch, t, v, ease=None, args=None):
        t = round(float(t), 4)
        vec = [_r(x) for x in v]
        if ease in ('catmullrom', 'step'):
            k = {'post': vec, 'lerp_mode': ease}          # как экспортирует сам Blockbench
        elif ease and ease != 'linear':
            k = {'vector': vec, 'easing': ease}          # как экспортирует плагин GeckoLib
            if args is not None:
                k['easingArgs'] = list(args)
        else:
            k = vec
        self._ch(bone, ch)[t] = k
        return self

    def rot(self, bone, t, v, ease='catmullrom', args=None):
        return self.key(bone, 'rotation', t, v, ease, args)

    def pos(self, bone, t, v, ease='catmullrom', args=None):
        return self.key(bone, 'position', t, v, ease, args)

    def scale(self, bone, t, v, ease='catmullrom', args=None):
        return self.key(bone, 'scale', t, v, ease, args)

    def cycle(self, bone, ch, fn, samples=12, ease='catmullrom'):
        """Периодическая кривая: fn(фаза 0..1) → (x, y, z); ключи по всему циклу, последний = первому."""
        for i in range(samples + 1):
            ph = (i % samples) / samples
            self.key(bone, ch, self.length * i / samples, fn(ph), ease)
        return self

    def track(self, bone, ch, fn, times, ease='catmullrom'):
        """Ключи кривой fn(t секунд) в заданные моменты."""
        for t in times:
            self.key(bone, ch, t, fn(t), ease)
        return self

    def marker(self, t, text):
        self.markers.append((round(float(t), 4), text))
        return self

    def sound(self, t, effect):
        self.sounds.append((round(float(t), 4), effect))
        return self

    def to_json(self):
        out = {}
        if self.loop is True:
            out['loop'] = True
        elif self.loop == 'hold':
            out['loop'] = 'hold_on_last_frame'
        out['animation_length'] = _r(self.length)
        bones = {}
        for b in sorted(self.bones, key=lambda n: R15.index(n) if n in R15 else 99):
            chans = {}
            for ch in ('rotation', 'position', 'scale'):
                keys = self.bones[b].get(ch)
                if not keys:
                    continue
                chans[ch] = {('%g' % t if t != int(t) else '%.1f' % t): k for t, k in sorted(keys.items())}
            bones[b] = chans
        out['bones'] = bones
        timeline = {}
        props = ['@%s=%s' % (k, v) for k, v in self.props.items()]
        if props:
            timeline[0.0] = props
        for t, text in self.markers:
            timeline.setdefault(t, []).append(text)
        if timeline:
            out['timeline'] = {('%g' % t if t != int(t) else '%.1f' % t): (v if len(v) > 1 else v[0])
                               for t, v in sorted(timeline.items())}
        if self.sounds:
            out['sound_effects'] = {('%g' % t if t != int(t) else '%.1f' % t): {'effect': e} for t, e in self.sounds}
        return out


def write(filename, anims, out_dir=ANIM_DIR):
    os.makedirs(out_dir, exist_ok=True)
    data = {'format_version': '1.8.0',
            'animations': {'animation.player.' + a.name: a.to_json() for a in anims}}
    path = os.path.join(out_dir, filename)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('wrote', os.path.relpath(path, ROOT), '(%d)' % len(anims))
    return path


# ---------------------------------------------------------------- помощники для кривых

def wave(ph, amp, shift=0.0, harmonics=None):
    """amp·cos(2π(ph − shift)) (+ гармоники: [(k, amp_k, shift_k)])."""
    v = amp * math.cos(2 * math.pi * (ph - shift))
    for k, a, s in harmonics or ():
        v += a * math.cos(2 * math.pi * (k * ph - s))
    return v


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def lerp(a, b, t):
    return a + (b - a) * t


def leg_ik(f, y, l1=6.0, l2=4.0):
    """Нога в плоскости шага: голеностоп в точке (f вперёд, y вверх) от тазобедренного сустава, пиксели.
    Возвращает (бедро X, колено X) в градусах Blockbench (колено сгибается назад)."""
    d = math.hypot(f, y)
    d = max(abs(l1 - l2) + 1e-3, min(l1 + l2 - 1e-3, d))
    alpha = math.atan2(f, -y)  # угол линии вперёд от вертикали вниз
    beta = math.acos(max(-1, min(1, (l1 * l1 + d * d - l2 * l2) / (2 * l1 * d))))
    gamma = math.acos(max(-1, min(1, (l1 * l1 + l2 * l2 - d * d) / (2 * l1 * l2))))
    thigh = alpha + beta
    knee = math.pi - gamma
    return -math.degrees(thigh), math.degrees(knee)
