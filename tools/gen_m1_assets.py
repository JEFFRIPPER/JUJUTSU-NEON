#!/usr/bin/env python3
"""M1-комбо: анимации тела и звуки.

Анимации (player_animation/):
  m1_jab_r.json / m1_jab_l.json — быстрый удар правой / левой: короткий замах от стойки, выпрямление
                                  руки с поворотом корпуса, возврат в стойку (только руки, корпус, голова —
                                  ноги ванильные, можно идти)
  m1_final.json   — мощный правый: замах с разворотом корпуса, удар всем телом на 4-м тике (FINAL_HIT_DELAY)
  m1_knockdown.json — сбит с ног: отлетает, падает на спину, лежит, поднимается
Звуки (sounds/m1/, свой синтез, моно):
  m1_swing — свист руки
  m1_hit   — попадание кулаком: плотный шлепок и удар
  m1_final — мощный удар: тяжёлый удар, хлопок воздуха, низкий гул
  m1_block — кулаком по блоку: глухой стук и крошка
"""
import json
import math
import subprocess
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon"
ANIM = ROOT / "player_animation"
SND = ROOT / "sounds/m1"
ANIM.mkdir(parents=True, exist_ok=True)
SND.mkdir(parents=True, exist_ok=True)

# ============================================================================ анимации
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


class Anim:
    def __init__(self, parts=None):
        self.keys = []
        self.parts = parts

    def key(self, tick, ease="INOUTSINE", **ch):
        self.keys.append((tick, {k.replace("__", "."): v for k, v in ch.items()}, ease))

    def write(self, name, end, stop):
        pose = dict(NEUTRAL)
        moves = []
        for tick, ch, ease in self.keys:
            pose.update(ch)
            by = {}
            for k, v in pose.items():
                part, c = k.split(".")
                if self.parts is not None and part not in self.parts:
                    continue
                by.setdefault(part, {})[c] = round(float(v), 3)
            m = {"tick": tick, "easing": "EASE" + ease, "turn": 0}
            m.update(by)
            moves.append(m)
        data = {"version": 3, "name": name, "author": "Jujutsu Neon", "description": name,
                "emote": {"beginTick": 0, "endTick": end, "stopTick": stop, "isLoop": False, "returnTick": 0,
                          "nsfw": False, "degrees": True, "moves": moves}}
        (ANIM / f"{name}.json").write_text(json.dumps(data, indent=1))
        print(name)


UPPER = {"rightArm", "leftArm", "torso", "head"}


def jab(left):
    s = -1 if left else 1          # зеркало: знак поворота корпуса
    hit, guard = ("leftArm", "rightArm") if left else ("rightArm", "leftArm")
    hy, gy = (-1 * s, 1 * s)       # yaw «внутрь» у ударной и у защитной руки
    a = Anim(UPPER)
    a.key(0)
    # замах: кулак у подбородка, корпус уводит ударное плечо назад
    a.key(1, "OUTQUAD", **{f"{hit}__pitch": -62, f"{hit}__yaw": 6 * hy, f"{hit}__roll": 10 * s, f"{hit}__bend": -108,
                           f"{guard}__pitch": -70, f"{guard}__yaw": 28 * gy, f"{guard}__roll": -6 * s, f"{guard}__bend": -108,
                           "torso__yaw": 10 * s})
    # удар: рука выпрямляется вперёд, корпус доворачивается
    a.key(3, "OUTQUAD", **{f"{hit}__pitch": -92, f"{hit}__yaw": 10 * hy, f"{hit}__roll": 2 * s, f"{hit}__bend": -2,
                           f"{guard}__pitch": -64, f"{guard}__yaw": 32 * gy, f"{guard}__bend": -112,
                           "torso__yaw": -18 * s, "torso__bend": 4})
    a.key(5, **{f"{hit}__pitch": -89, f"{hit}__bend": -10, "torso__yaw": -13 * s})
    # обратно в стойку (кулаки у лица)
    a.key(10, **{f"{hit}__pitch": -62, f"{hit}__yaw": 26 * hy, f"{hit}__roll": 6 * s, f"{hit}__bend": -105,
                 f"{guard}__pitch": -66, f"{guard}__yaw": 28 * gy, f"{guard}__bend": -105,
                 "torso__yaw": 0, "torso__bend": 0})
    a.write("m1_jab_l" if left else "m1_jab_r", 10, 16)


def final():
    a = Anim(UPPER)
    a.key(0)
    # замах: правый локоть далеко назад, корпус развёрнут, левая рука вытянута — целится
    a.key(2, "OUTQUAD", rightArm__pitch=-32, rightArm__yaw=10, rightArm__roll=40, rightArm__bend=-115,
          leftArm__pitch=-82, leftArm__yaw=18, leftArm__roll=-4, leftArm__bend=-36,
          torso__yaw=26, torso__bend=-4, head__pitch=2)
    a.key(3, rightArm__pitch=-26, rightArm__roll=44, torso__yaw=30, torso__bend=-5)
    # УДАР: всем телом, правая прямая, левая уходит назад
    a.key(4, "OUTQUAD", rightArm__pitch=-96, rightArm__yaw=-6, rightArm__roll=0, rightArm__bend=0,
          leftArm__pitch=12, leftArm__yaw=0, leftArm__roll=-20, leftArm__bend=-30,
          torso__yaw=-28, torso__bend=8, head__pitch=4)
    a.key(8, rightArm__pitch=-92, rightArm__bend=-6, torso__yaw=-24, torso__bend=6)
    a.key(16, **{k.replace(".", "__"): v for k, v in NEUTRAL.items() if k.split(".")[0] in UPPER})
    a.write("m1_final", 16, 22)


def knockdown():
    a = Anim()
    a.key(0)
    # отлетает: корпус запрокинут, руки и ноги вразлёт
    a.key(3, "OUTQUAD", body__pitch=48, body__y=-0.08, head__pitch=-18,
          rightArm__pitch=-140, rightArm__roll=30, rightArm__bend=-20,
          leftArm__pitch=-150, leftArm__roll=-30, leftArm__bend=-20,
          rightLeg__pitch=-40, rightLeg__bend=30, leftLeg__pitch=-22, leftLeg__bend=22)
    # упал на спину
    a.key(7, "INQUAD", body__pitch=88, body__y=-0.56, head__pitch=10,
          rightArm__pitch=-170, rightArm__roll=40, rightArm__bend=-10,
          leftArm__pitch=-165, leftArm__roll=-45, leftArm__bend=-10,
          rightLeg__pitch=-10, rightLeg__bend=10, leftLeg__pitch=-4, leftLeg__bend=6)
    a.key(9, "OUTQUAD", body__y=-0.52)
    a.key(11, "INQUAD", body__y=-0.56)
    a.key(20, body__y=-0.56, head__pitch=6)
    # поднимается
    a.key(26, "INOUTQUAD", **{k.replace(".", "__"): v for k, v in NEUTRAL.items()})
    a.write("m1_knockdown", 26, 30)


# ============================================================================ звуки
SR = 48000
RNG = np.random.default_rng(91)


def t_axis(d):
    return np.arange(int(SR * d)) / SR


def noise(d):
    return RNG.standard_normal(int(SR * d))


def band(x, lo, hi):
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = np.fft.rfft(x)
    lo_w = 1 / (1 + (lo / np.maximum(f, 1)) ** 4) if lo > 0 else 1
    hi_w = 1 / (1 + (f / hi) ** 4) if hi > 0 else 1
    return np.fft.irfft(spec * lo_w * hi_w, n)


def moving_band(x, f_lo, f_hi, width=0.6, blocks=48):
    out = np.zeros_like(x)
    n = len(x)
    step = n // blocks
    for i in range(blocks):
        a = max(0, i * step - step)
        b = min(n, (i + 1) * step + step)
        k = i / max(1, blocks - 1)
        fc = f_lo * (f_hi / f_lo) ** k
        out[a:b] += band(x[a:b], fc * (1 - width / 2), fc * (1 + width)) * np.hanning(b - a)
    return out


def env(t, attack, decay, power=1.0):
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.clip(1 - (t - attack) / max(decay, 1e-4), 0, 1) ** power
    return np.where(t < attack, a, d)


def thump(d, f0, f1, decay, rate=22):
    t = t_axis(d)
    return np.sin(2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t * rate)) / SR) * np.exp(-t / decay)


def place(dst, src, at):
    i = int(at * SR)
    n = min(len(src), len(dst) - i)
    if n > 0:
        dst[i:i + n] += src[:n]


def reverb(x, length=0.6, mix=0.18, lo=200, hi=6000, decay=7.0):
    x = x.copy()
    k = int(SR * 0.05)
    x[-k:] *= np.linspace(1, 0, k)
    ir_t = t_axis(length)
    ir = band(noise(length), lo, hi) * np.exp(-ir_t * decay)
    ir /= np.sqrt((ir ** 2).sum())
    wet = np.convolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 2.5


def finish(name, x, gain_db=-1.0, drive=1.8):
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * drive) / np.tanh(drive)
    x *= 10 ** (gain_db / 20)
    fade = min(len(x), int(SR * 0.02))
    x[-fade:] *= np.linspace(1, 0, fade)
    wav = SND / f"{name}.wav"
    with wave.open(str(wav), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32000).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "7",
                    str(SND / f"{name}.ogg")], check=True)
    wav.unlink()
    print(name, round(len(x) / SR, 2), "s")


def swing():
    d = 0.32
    t = t_axis(d)
    w = moving_band(noise(d), 700, 2600, 0.7) * env(t, 0.06, 0.22, 1.8)
    finish("m1_swing", w, -6.0, 1.2)


def hit():
    d = 0.45
    t = t_axis(d)
    x = np.zeros_like(t)
    place(x, thump(0.3, 190, 70, 0.06), 0.0)
    slap = band(noise(d), 900, 7000) * env(t, 0.001, 0.035, 2.5) * 1.3
    body = band(noise(d), 120, 900) * env(t, 0.002, 0.09, 2.2) * 0.9
    finish("m1_hit", reverb(x * 1.2 + slap + body, 0.4, 0.14), -1.0, 2.2)


def final_hit():
    d = 1.2
    t = t_axis(d)
    x = np.zeros_like(t)
    place(x, thump(0.9, 150, 36, 0.2, 16), 0.0)
    crack = band(noise(d), 1200, 10000) * env(t, 0.001, 0.05, 2.5) * 1.6
    body = band(noise(d), 80, 800) * env(t, 0.002, 0.25, 2.0) * 1.2
    burst = moving_band(noise(d), 2000, 300, 0.8) * env(t, 0.01, 0.7, 1.6) * 0.8
    rumble = band(noise(d), 25, 120) * env(t, 0.01, 1.0, 1.5) * 0.9
    finish("m1_final", reverb(x * 1.4 + crack + body + burst + rumble, 0.9, 0.2, 100, 5000, 4.0), -0.5, 2.4)


def block():
    d = 0.5
    t = t_axis(d)
    x = np.zeros_like(t)
    place(x, thump(0.3, 140, 60, 0.05), 0.0)
    knock = band(noise(d), 300, 3000) * env(t, 0.001, 0.06, 2.5) * 1.1
    rng = np.random.default_rng(4)
    grit = np.zeros_like(t)
    for _ in range(14):
        at = rng.uniform(0.02, 0.22)
        tt = t_axis(0.03)
        place(grit, band(rng.standard_normal(len(tt)), 2000, 8000) * np.exp(-tt / 0.006) * rng.uniform(0.2, 0.5), at)
    finish("m1_block", reverb(x + knock + grit, 0.3, 0.12), -2.0, 2.0)


jab(False)
jab(True)
final()
knockdown()
swing()
hit()
final_hit()
block()
