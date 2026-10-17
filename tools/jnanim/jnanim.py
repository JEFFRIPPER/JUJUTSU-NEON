#!/usr/bin/env python3
"""jnanim — авторинг покадровых анимаций Jujutsu Neon.

Анимация — это поза на КАЖДЫЙ кадр (по умолчанию 24 к/с). Библиотека позволяет:
  * задать ключи с кривыми и запечь их в каждый кадр (FrameAnim.from_keys / .key);
  * править любой отдельный кадр (anim[кадр]["rightArm.pitch"] = -90);
  * наложить запечённое вторичное движение (пружины: запаздывание и захлёст) — .bake_springs();
  * зеркалить, менять fps без потери формы (передискретизация Кэтмелла–Рома), склеивать, смешивать;
  * читать все форматы мода (jn_frames, jn_keys, эмоции Player Animator) и писать jn_frames.

Единицы и знаки — как в FrameChannel.java (градусы; body.x/y/z — блоки; смещения частей — пиксели):
  рука/нога pitch < 0 — вперёд-вверх; правая roll > 0 и левая roll < 0 — наружу;
  правая yaw < 0 и левая yaw > 0 — внутрь; сгиб руки < 0 — локоть, сгиб ноги > 0 — колено;
  head pitch > 0 — вниз; torso bend > 0 — вперёд; body pitch > 0 — назад, yaw > 0 — влево, roll > 0 — влево.

Пример:
    from jnanim import FrameAnim
    a = FrameAnim("jab", fps=24, length=12)
    a.key(0, {"rightArm.pitch": -60, "rightArm.bend": -100})
    a.key(4, {"rightArm.pitch": -92, "rightArm.bend": -2}, ease="outquad")
    a.key(11, {"rightArm.pitch": -60, "rightArm.bend": -100}, ease="inoutsine")
    a.bake()                      # каждый кадр заполнен
    a[6]["head.yaw"] = 4          # ручная правка одного кадра
    a.save("jab.json")
"""
import json
import math
from pathlib import Path

PARTS = ["body", "head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg"]
LIMB_KINDS = ["x", "y", "z", "pitch", "yaw", "roll", "bend", "bend_axis"]
CHANNELS = (["body.x", "body.y", "body.z", "body.pitch", "body.yaw", "body.roll"]
            + ["head.x", "head.y", "head.z", "head.pitch", "head.yaw", "head.roll"]
            + [f"torso.{k}" for k in LIMB_KINDS]
            + [f"{p}.{k}" for p in ("rightArm", "leftArm", "rightLeg", "leftLeg") for k in LIMB_KINDS])
ANGLE_KINDS = {"pitch", "yaw", "roll", "bend", "bend_axis"}

ALIASES = {"right_arm": "rightArm", "left_arm": "leftArm", "right_leg": "rightLeg", "left_leg": "leftLeg",
           "root": "body", "chest": "torso"}


def norm_channel(key):
    part, _, kind = key.partition(".")
    part = ALIASES.get(part.lower(), part)
    for p in PARTS:
        if p.lower() == part.lower():
            part = p
    kind = kind.lower()
    if kind in ("axis", "bendaxis"):
        kind = "bend_axis"
    ch = f"{part}.{kind}"
    if ch not in CHANNELS:
        raise KeyError(f"неизвестный канал: {key}")
    return ch


def mirror_channel(ch):
    part, kind = ch.split(".")
    swap = {"rightArm": "leftArm", "leftArm": "rightArm", "rightLeg": "leftLeg", "leftLeg": "rightLeg"}
    sign = -1.0 if kind in ("yaw", "roll", "x", "bend_axis") else 1.0
    return f"{swap.get(part, part)}.{kind}", sign


# ============================================================================ кривые
def _bounce_out(t):
    n1, d1 = 7.5625, 2.75
    if t < 1 / d1:
        return n1 * t * t
    if t < 2 / d1:
        t -= 1.5 / d1
        return n1 * t * t + 0.75
    if t < 2.5 / d1:
        t -= 2.25 / d1
        return n1 * t * t + 0.9375
    t -= 2.625 / d1
    return n1 * t * t + 0.984375


_BASE_IN = {
    "sine": lambda t: 1 - math.cos(t * math.pi / 2),
    "quad": lambda t: t * t,
    "cubic": lambda t: t ** 3,
    "quart": lambda t: t ** 4,
    "quint": lambda t: t ** 5,
    "expo": lambda t: 0.0 if t <= 0 else 2 ** (10 * t - 10),
    "circ": lambda t: 1 - math.sqrt(max(0.0, 1 - t * t)),
    "back": lambda t: 2.70158 * t ** 3 - 1.70158 * t * t,
    "elastic": lambda t: t if t <= 0 or t >= 1 else -(2 ** (10 * t - 10)) * math.sin((t * 10 - 10.75) * 2 * math.pi / 3),
    "bounce": lambda t: 1 - _bounce_out(1 - t),
}


def bezier(x1, y1, x2, y2):
    def bz(u, p1, p2):
        v = 1 - u
        return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u ** 3

    def f(t):
        if t <= 0:
            return 0.0
        if t >= 1:
            return 1.0
        lo, hi = 0.0, 1.0
        u = t
        for _ in range(40):
            if bz(u, x1, x2) < t:
                lo = u
            else:
                hi = u
            u = (lo + hi) / 2
        return bz(u, y1, y2)
    return f


def easing(name):
    if callable(name):
        return name
    if isinstance(name, (list, tuple)) and len(name) == 4:
        return bezier(*name)
    n = (name or "linear").lower().replace("_", "").replace("-", "")
    if n.startswith("ease"):
        n = n[4:]
    if n in ("", "linear"):
        return lambda t: t
    if n in ("step", "hold", "constant"):
        return lambda t: 1.0 if t >= 1 else 0.0
    if n in ("smooth", "smoothstep"):
        return lambda t: t * t * (3 - 2 * t)
    if n in ("smoother", "smootherstep"):
        return lambda t: t ** 3 * (t * (t * 6 - 15) + 10)
    if n.startswith("inout"):
        d, b = "inout", n[5:]
    elif n.startswith("in"):
        d, b = "in", n[2:]
    elif n.startswith("out"):
        d, b = "out", n[3:]
    else:
        d, b = "inout", n
    fin = _BASE_IN.get(b)
    if fin is None:
        return lambda t: t
    if d == "in":
        return fin
    if d == "out":
        return lambda t: 1 - fin(1 - t)
    return lambda t: fin(t * 2) / 2 if t < 0.5 else 1 - fin((1 - t) * 2) / 2


def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


# ============================================================================ анимация
class FrameAnim:
    def __init__(self, name, fps=24, length=1, **meta):
        self.name = name
        self.fps = float(fps)
        self.frames = [dict() for _ in range(max(1, int(length)))]
        self.events = []          # [(кадр, имя, данные)]
        self.meta = {"loop": False, "interpolation": "catmull", "fade_in": 0.1, "fade_out": 0.15,
                     "head": "absolute"}
        self.meta.update(meta)
        self._keys = {}           # канал → [(кадр, значение, кривая)]

    # ---------------------------------------------------------------- доступ
    def __len__(self):
        return len(self.frames)

    def __getitem__(self, i):
        return self.frames[i]

    @property
    def duration(self):
        return len(self.frames) / self.fps

    def resize(self, length):
        length = max(1, int(length))
        while len(self.frames) < length:
            self.frames.append(dict(self.frames[-1]) if self.frames else {})
        del self.frames[length:]

    def channels(self):
        used = set()
        for f in self.frames:
            used.update(k for k, v in f.items() if v is not None)
        return [c for c in CHANNELS if c in used]

    def value(self, frame, ch):
        """Значение канала в дробном кадре (Кэтмелл–Ром, как в игре)."""
        n = len(self.frames)
        i = int(math.floor(frame))
        u = frame - i

        def at(k):
            k = min(max(k, 0), n - 1)
            return self.frames[k].get(ch)
        p1, p2 = at(i), at(i + 1)
        if p1 is None:
            return p2 if u >= 0.5 else None
        if p2 is None or u <= 0:
            return p1
        p0, p3 = at(i - 1), at(i + 2)
        return catmull(p0 if p0 is not None else p1, p1, p2, p3 if p3 is not None else p2, u)

    # ---------------------------------------------------------------- ключи
    def key(self, frame, pose, ease="linear"):
        """Ключ: поза (словарь канал → значение) в кадре; ease — переход В этот ключ."""
        for k, v in pose.items():
            ch = norm_channel(k)
            self._keys.setdefault(ch, []).append((float(frame), float(v), easing(ease)))
        if frame + 1 > len(self.frames):
            self.resize(int(math.ceil(frame)) + 1)
        return self

    def bake(self):
        """Запечь все ключи в каждый кадр."""
        for ch, keys in self._keys.items():
            keys.sort(key=lambda k: k[0])
            for f in range(len(self.frames)):
                self.frames[f][ch] = _eval_keys(keys, f)
        return self

    def event(self, frame, name, data=""):
        self.events.append((int(frame), name, data))
        return self

    # ---------------------------------------------------------------- операции
    def mirrored(self, name=None):
        out = FrameAnim(name or self.name + "_mirror", self.fps, len(self.frames), **self.meta)
        for i, f in enumerate(self.frames):
            for ch, v in f.items():
                m, s = mirror_channel(ch)
                out.frames[i][m] = None if v is None else v * s
        out.events = list(self.events)
        return out

    def resampled(self, fps):
        """Другая частота кадров без потери формы (каждый новый кадр — точное значение кривой)."""
        fps = float(fps)
        n = max(1, int(round(len(self.frames) * fps / self.fps)))
        out = FrameAnim(self.name, fps, n, **self.meta)
        chans = self.channels()
        for i in range(n):
            src = i * self.fps / fps
            for ch in chans:
                out.frames[i][ch] = self.value(src, ch)
        out.events = [(int(round(f * fps / self.fps)), nm, d) for f, nm, d in self.events]
        return out

    def offset(self, deltas, frames=None):
        """Прибавить к каналам (словарь) на всех или выбранных кадрах."""
        rng = range(len(self.frames)) if frames is None else frames
        for i in rng:
            for k, dv in deltas.items():
                ch = norm_channel(k)
                v = self.frames[i].get(ch)
                self.frames[i][ch] = (v or 0.0) + dv
        return self

    def bake_springs(self, stiffness=140.0, damping=13.0, parts=None, amount=1.0, substeps=10):
        """Запечённое вторичное движение: каждый угол догоняет позу пружиной (запаздывание + захлёст)."""
        parts = parts or {"rightArm": 1.0, "leftArm": 1.0, "rightLeg": 0.4, "leftLeg": 0.4, "torso": 0.6, "head": 0.7}
        dt = 1.0 / self.fps / substeps
        for ch in self.channels():
            part, kind = ch.split(".")
            w = parts.get(part, 0.0) * amount
            if w <= 0 or kind not in ANGLE_KINDS or kind == "bend_axis":
                continue
            x = None
            v = 0.0
            for i in range(len(self.frames)):
                target = self.frames[i].get(ch)
                if target is None:
                    x = None
                    continue
                if x is None:
                    x = target
                for _ in range(substeps):
                    a = stiffness * (target - x) - damping * v
                    v += a * dt
                    x += v * dt
                self.frames[i][ch] = target + (x - target) * min(1.0, w)
        return self

    def concat(self, other, crossfade=0):
        """Склеить с другой анимацией (с плавным переходом на crossfade кадров)."""
        if other.fps != self.fps:
            other = other.resampled(self.fps)
        base = len(self.frames)
        start = base - crossfade
        self.resize(start + len(other.frames))
        for j, f in enumerate(other.frames):
            i = start + j
            if j < crossfade:
                k = (j + 1) / (crossfade + 1)
                k = k * k * (3 - 2 * k)
                for ch, v in f.items():
                    a = self.frames[i].get(ch)
                    self.frames[i][ch] = v if a is None or v is None else a + (v - a) * k
            else:
                self.frames[i] = dict(f)
        self.events += [(f + start, n, d) for f, n, d in other.events]
        return self

    # ---------------------------------------------------------------- файлы
    def to_json(self):
        chans = self.channels()
        data = {"format": "jn_frames", "name": self.name, "fps": self.fps}
        data.update(self.meta)
        if self.events:
            data["events"] = [{"frame": f, "name": n, **({"data": d} if d else {})} for f, n, d in sorted(self.events)]
        data["channels"] = chans
        data["frames"] = [[_r(f.get(c)) for c in chans] for f in self.frames]
        # компактно: каждый кадр в одну строку
        head = json.dumps({k: v for k, v in data.items() if k not in ("frames",)}, ensure_ascii=False, indent=2)[:-2]
        rows = ",\n".join("    " + json.dumps(r) for r in data["frames"])
        return head + ",\n  \"frames\": [\n" + rows + "\n  ]\n}\n"

    def save(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(self.to_json(), encoding="utf-8")
        return path

    @staticmethod
    def load(path, name=None):
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return FrameAnim.from_data(data, name or Path(path).stem)

    @staticmethod
    def from_data(data, name):
        if "emote" in data:
            return _from_emote(data, name)
        a = FrameAnim(data.get("name", name), data.get("fps", 24), 1)
        for k in ("loop", "loop_from", "hold_last", "interpolation", "fade_in", "fade_out", "head", "first_person",
                  "clock", "speed", "secondary"):
            if k in data:
                a.meta[k] = data[k]
        for e in data.get("events", []):
            a.events.append((e.get("frame", 0), e.get("name", "event"), e.get("data", "")))
        if "frames" in data:
            frames = data["frames"]
            a.resize(len(frames))
            chans = [norm_channel(c) for c in data.get("channels", [])]
            carry = {}
            for i, fr in enumerate(frames):
                if isinstance(fr, list):
                    a.frames[i] = {c: v for c, v in zip(chans, fr) if v is not None}
                else:
                    for k, v in fr.items():
                        carry[norm_channel(k)] = v
                    a.frames[i] = {k: v for k, v in carry.items() if v is not None}
        else:
            for ch, keys in data.get("tracks", {}).items():
                for k in keys:
                    if isinstance(k, list):
                        a.key(k[0], {ch: k[1]}, k[2] if len(k) > 2 else "linear")
                    else:
                        a.key(k["frame"], {ch: k["value"]}, k.get("ease", "linear"))
            for k in data.get("keys", []):
                a.key(k["frame"], k.get("pose", {}), k.get("ease", "linear"))
            if "length" in data:
                a.resize(int(math.ceil(data["length"])))
            elif "duration" in data:
                a.resize(int(math.ceil(data["duration"] * a.fps)))
            a.bake()
        return a


def _r(v):
    if v is None:
        return None
    r = round(v, 3)
    return int(r) if r == int(r) else r


def _eval_keys(keys, f):
    if f <= keys[0][0]:
        return keys[0][1]
    if f >= keys[-1][0]:
        return keys[-1][1]
    for i in range(1, len(keys)):
        f1, v1, e1 = keys[i]
        if f <= f1:
            f0, v0, _ = keys[i - 1]
            u = 1.0 if f1 == f0 else (f - f0) / (f1 - f0)
            return v0 + (v1 - v0) * e1(u)
    return keys[-1][1]


def _from_emote(data, name):
    """Эмоция Player Animator → кадры (20 к/с), так же, как её проигрывает Player Animator."""
    em = data["emote"]
    begin, end = em.get("beginTick", 0), em.get("endTick", 1)
    stop = em.get("stopTick", end)
    deg = em.get("degrees", True)
    ease_before = em.get("easeBeforeKeyframe", False)
    tracks = {}
    for mv in em.get("moves", []):
        tick = mv.get("tick", 0)
        e = easing(mv.get("easing", "linear"))
        for part, chs in mv.items():
            if not isinstance(chs, dict):
                continue
            for k, v in chs.items():
                try:
                    ch = norm_channel(f"{part}.{k}")
                except KeyError:
                    continue
                kind = ch.split(".")[1]
                if kind in ANGLE_KINDS and not deg:
                    v = math.degrees(v)
                tracks.setdefault(ch, []).append((tick, v, e))
    a = FrameAnim(data.get("name", name), 20, end - begin + 1, fade_in=0, fade_out=max(0, stop - end) / 20.0,
                  loop=em.get("isLoop", False))
    for ch, ks in tracks.items():
        ks.sort(key=lambda k: k[0])
        for f in range(len(a.frames)):
            t = begin + f
            if t <= ks[0][0]:
                a.frames[f][ch] = ks[0][1]
                continue
            val = ks[-1][1]
            for i in range(1, len(ks)):
                if t <= ks[i][0]:
                    t0, v0, e0 = ks[i - 1]
                    t1, v1, e1 = ks[i]
                    u = 1.0 if t1 == t0 else (t - t0) / (t1 - t0)
                    val = v0 + (v1 - v0) * (e1 if ease_before else e0)(u)
                    break
            a.frames[f][ch] = val
    return a
