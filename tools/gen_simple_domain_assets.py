#!/usr/bin/env python3
"""Простая территория: текстуры, звуки и анимация тела.

Текстуры (белые — цвет задаёт вершина), textures/gui/:
  simple_flame.png — бесшовная по горизонтали лента «пламени-вихря» для стены круга (низ — у земли)
  simple_floor.png — заливка пола: полупрозрачный диск с зерном и светлым краем
Звуки (свой синтез, моно), sounds/cursed/:
  simple_domain_cast  — раскрытие: закручивающийся порыв, мягкий звон, «вдох» круга
  simple_domain_hit   — удар поглощён: глухой толчок и звенящая рябь
  simple_domain_break — третий удар: круг лопается — стеклянный треск и выдох ветра
  simple_domain_end   — круг сжимается и гаснет
Анимация (player_animation/simple_domain.json): присед с печатью у груди, 0,8 с, потом встаёт.
"""
import json
import math
import subprocess
import wave
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon"
TEX = ROOT / "textures/gui"
SND = ROOT / "sounds/cursed"
ANIM = ROOT / "player_animation"
for d in (TEX, SND, ANIM):
    d.mkdir(parents=True, exist_ok=True)


# ============================================================================ текстуры
def save(a, name):
    a = np.clip(a, 0, 1)
    img = np.dstack([np.ones(a.shape + (3,)), a])
    Image.fromarray((img * 255).astype(np.uint8), "RGBA").save(TEX / name, optimize=True)


def periodic_noise(w, h, freqs, seed):
    """Шум, бесшовный по горизонтали (сумма синусов с целыми частотами по x)."""
    rng = np.random.default_rng(seed)
    x = np.linspace(0, 2 * np.pi, w, endpoint=False)[None, :]
    y = np.linspace(0, 1, h)[:, None]
    out = np.zeros((h, w))
    for f in freqs:
        for _ in range(3):
            ph, py, amp = rng.uniform(0, 2 * np.pi), rng.uniform(0, 2 * np.pi), rng.uniform(0.5, 1.0) / f ** 0.6
            out += amp * np.sin(f * x + ph + np.sin(y * 3.0 * rng.uniform(1, 4) + py) * 0.8)
    out -= out.min()
    return out / out.max()


def flame():
    w, h = 512, 192
    x = np.linspace(0, 2 * np.pi, w, endpoint=False)
    rng = np.random.default_rng(7)
    # профиль высоты языков: несколько острых пиков, бесшовно
    prof = np.zeros(w)
    for f in (3, 5, 7, 11, 13, 17):
        prof += rng.uniform(0.3, 1.0) / f ** 0.5 * np.sin(f * x + rng.uniform(0, 6.28))
    prof = (prof - prof.min()) / (prof.max() - prof.min())
    prof = 0.28 + 0.72 * prof ** 1.6
    yb = np.linspace(1, 0, h)[:, None]  # 0 — низ (земля), 1 — верх
    n = periodic_noise(w, h, (6, 14, 28), 3)
    core = np.clip((prof[None, :] - yb) / prof[None, :], 0, 1)
    a = core ** 1.3 * (0.55 + 0.45 * n)
    # волокна, закрученные вбок
    fib = 0.75 + 0.25 * np.sin(x[None, :] * 24 + yb * 9 + n * 4)
    a = a * fib + np.exp(-(yb / 0.08) ** 2) * 0.6  # яркая кромка у земли
    save(a, "simple_flame.png")


def floor():
    s = 512
    yy, xx = np.mgrid[0:s, 0:s]
    c = (s - 1) / 2
    r = np.sqrt((xx - c) ** 2 + (yy - c) ** 2) / c
    rng = np.random.default_rng(11)
    grain = rng.random((s, s))
    # мягкие пятна без направления: случайный шум через низкочастотный фильтр
    f = np.fft.fft2(rng.standard_normal((s, s)))
    fy = np.fft.fftfreq(s)[:, None]
    fx = np.fft.fftfreq(s)[None, :]
    n = np.real(np.fft.ifft2(f * np.exp(-(fx ** 2 + fy ** 2) / (2 * 0.012 ** 2))))
    n = (n - n.min()) / (n.max() - n.min())
    a = 0.20 + 0.16 * n + 0.10 * (grain > 0.985) + 0.10 * r ** 3
    a += np.exp(-((r - 0.96) / 0.035) ** 2) * 0.7 + np.exp(-((r - 0.88) / 0.10) ** 2) * 0.25
    a *= np.clip((1.0 - r) / 0.02, 0, 1)
    save(a, "simple_floor.png")


# ============================================================================ звуки
SR = 48000
RNG = np.random.default_rng(77)


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


def moving_band(x, f_lo, f_hi, width=0.6, blocks=64):
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


def tone(t, f, detune=0.003):
    f = np.broadcast_to(f, t.shape)
    return 0.5 * (np.sin(2 * np.pi * np.cumsum(f) / SR) + np.sin(2 * np.pi * np.cumsum(f * (1 + detune)) / SR + 1.1))


def thump(d, f0, f1, decay, rate=18):
    t = t_axis(d)
    return np.sin(2 * np.pi * np.cumsum(f1 + (f0 - f1) * np.exp(-t * rate)) / SR) * np.exp(-t / decay)


def place(dst, src, at):
    i = int(at * SR)
    n = min(len(src), len(dst) - i)
    if n > 0:
        dst[i:i + n] += src[:n]


def reverb(x, length=1.4, mix=0.3, lo=200, hi=7000, decay=3.5):
    x = x.copy()
    k = int(SR * 0.2)
    x[-k:] *= np.linspace(1, 0, k) ** 2
    ir_t = t_axis(length)
    ir = band(noise(length), lo, hi) * np.exp(-ir_t * decay)
    ir /= np.sqrt((ir ** 2).sum())
    wet = np.convolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 2.5


def finish(name, x, gain_db=-1.5, drive=1.3):
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * drive) / np.tanh(drive)
    x *= 10 ** (gain_db / 20)
    fade = min(len(x), int(SR * 0.05))
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


def sd_cast():
    d = 2.4
    t = t_axis(d)
    x = np.zeros_like(t)
    place(x, thump(0.6, 120, 45, 0.14), 0.0)
    # закручивающийся порыв: полоса ветра идёт вверх и «кружит» (амплитудная модуляция ускоряется)
    swirl = moving_band(noise(d), 250, 2600, 0.8) * env(t, 0.35, 1.8, 1.5)
    swirl *= 0.7 + 0.3 * np.sin(2 * np.pi * np.cumsum(3 + 6 * np.clip(t / 0.9, 0, 1)) / SR)
    # мягкий звон — квинта, расходится вместе с кругом
    chime = (tone(t, 784) * 0.5 + tone(t, 1175) * 0.35 + tone(t, 1568) * 0.2) * env(t, 0.25, 1.9, 1.7)
    shimmer = band(noise(d), 5000, 13000) * env(t, 0.4, 1.5, 2) * 0.15
    finish("simple_domain_cast", reverb(x * 0.8 + swirl * 1.1 + chime * 0.55 + shimmer, 1.8, 0.34, 250, 9000, 2.8))


def sd_hit():
    d = 0.9
    t = t_axis(d)
    x = np.zeros_like(t)
    place(x, thump(0.4, 160, 60, 0.08, 24), 0.0)
    ripple = tone(t, 620 * (1 + 0.06 * np.sin(2 * np.pi * 9 * t)), 0.006) * np.exp(-t / 0.28) * 0.6
    ring = (np.sin(2 * np.pi * 1860 * t) * 0.4 + np.sin(2 * np.pi * 2790 * t + 1) * 0.25) * np.exp(-t / 0.18)
    air = band(noise(d), 800, 5000) * env(t, 0.003, 0.2, 2.2) * 0.4
    finish("simple_domain_hit", reverb(x + ripple + ring * 0.6 + air, 0.9, 0.25, 300, 8000, 4.5), -2.0)


def sd_break():
    d = 1.8
    t = t_axis(d)
    rng = np.random.default_rng(3)
    # стеклянный треск: много коротких звонких осколков
    shards = np.zeros_like(t)
    for _ in range(70):
        at = abs(rng.normal(0.0, 0.12))
        f = rng.uniform(2200, 7500)
        tt = t_axis(0.18)
        place(shards, np.sin(2 * np.pi * f * tt) * np.exp(-tt / rng.uniform(0.02, 0.07)) * rng.uniform(0.2, 0.6), at)
    crack = band(noise(d), 1500, 11000) * env(t, 0.001, 0.12, 2.5) * 1.2
    x = np.zeros_like(t)
    place(x, thump(0.7, 140, 40, 0.16), 0.0)
    blow = moving_band(noise(d), 2400, 300, 0.8) * env(t, 0.02, 1.4, 1.6) * 0.8
    finish("simple_domain_break", reverb(shards + crack + x + blow, 1.5, 0.3, 300, 9000, 3.0))


def sd_end():
    d = 1.3
    t = t_axis(d)
    suck = moving_band(noise(d), 2200, 260, 0.8) * env(t, 0.1, 1.1, 1.4)
    chime = (tone(t, 1175 * (1 - 0.35 * np.clip(t / 1.1, 0, 1))) * 0.4) * env(t, 0.05, 1.0, 1.8)
    x = np.zeros_like(t)
    place(x, thump(0.4, 90, 45, 0.1), 0.95)
    finish("simple_domain_end", reverb(suck + chime + x * 0.6, 1.2, 0.3, 300, 8000, 3.5), -3.0)


# ============================================================================ анимация
MODEL_SCALE = 0.9375


def crouch_y(a):
    return round(-0.75 * MODEL_SCALE * (1.0 - math.cos(math.radians(a))), 3)


def anim():
    parts = ["head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg"]
    chans = {"head": ["pitch", "yaw", "roll"], "body": ["y", "pitch"]}
    for p in parts[1:]:
        chans[p] = ["pitch", "yaw", "roll", "bend"]
    neutral = {f"{p}.{c}": 0.0 for p, cs in chans.items() for c in cs}
    neutral.update({"rightArm.bend": -6, "leftArm.bend": -6, "rightArm.roll": 4, "leftArm.roll": -4})
    squat = {
        "body.y": crouch_y(62), "body.pitch": -10, "torso.bend": 16, "head.pitch": 6,
        "rightLeg.pitch": -64, "rightLeg.roll": 6, "rightLeg.bend": 118,
        "leftLeg.pitch": -58, "leftLeg.roll": -6, "leftLeg.bend": 110,
        # печать у груди: ладони сведены перед собой
        "rightArm.pitch": -72, "rightArm.yaw": -40, "rightArm.roll": 0, "rightArm.bend": -78,
        "leftArm.pitch": -72, "leftArm.yaw": 40, "leftArm.roll": 0, "leftArm.bend": -78,
    }
    hold = dict(squat)
    hold.update({"body.y": crouch_y(64), "torso.bend": 18, "head.pitch": 8})
    keys = [(0, "INOUTSINE", {}), (4, "OUTQUAD", squat), (11, "INOUTSINE", hold), (16, "INOUTSINE", squat),
            (22, "INOUTQUAD", dict(neutral))]
    pose = dict(neutral)
    moves = []
    for tick, ease, ch in keys:
        pose.update(ch)
        by = {}
        for k, v in pose.items():
            part, c = k.split(".")
            by.setdefault(part, {})[c] = round(float(v), 3)
        m = {"tick": tick, "easing": "EASE" + ease, "turn": 0}
        m.update(by)
        moves.append(m)
    data = {"version": 3, "name": "simple_domain", "author": "Jujutsu Neon", "description": "simple_domain",
            "emote": {"beginTick": 0, "endTick": 22, "stopTick": 27, "isLoop": False, "returnTick": 0,
                      "nsfw": False, "degrees": True, "moves": moves}}
    (ANIM / "simple_domain.json").write_text(json.dumps(data, indent=1))
    print("simple_domain.json")


flame()
floor()
sd_cast()
sd_hit()
sd_break()
sd_end()
anim()
