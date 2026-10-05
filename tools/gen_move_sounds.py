#!/usr/bin/env python3
"""Звуки движения (свой синтез, моно — звучат из точки в мире; в референсах звука не было).

Результат: src/main/resources/assets/jujutsu_neon/sounds/move/*.ogg
  move_dash_front — резкий срыв с места: удар по земле, рывок воздуха, шорох и скрип торможения
  move_dash_side  — короткий свист-рывок вбок с хлопком воздуха
  move_crouch     — подготовка к прыжку: нарастающий низкий гул и шорох
  move_takeoff    — взлёт: глухой удар о землю и закручивающийся вверх поток ветра
  move_boost      — включение ускоренного полёта: порыв ветра
  move_step       — шаг сверхбега: глухой удар и шорох
  move_land       — приземление из полёта
"""
import subprocess
import wave
from pathlib import Path

import numpy as np

SR = 48000
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/sounds/move"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(11)


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur):
    return RNG.standard_normal(int(SR * dur))


def band(x, lo, hi):
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = np.fft.rfft(x)
    lo_w = 1 / (1 + (lo / np.maximum(f, 1)) ** 4) if lo > 0 else 1
    hi_w = 1 / (1 + (f / hi) ** 4) if hi > 0 else 1
    return np.fft.irfft(spec * lo_w * hi_w, n)


def moving_band(x, f_lo, f_hi, width=0.6, blocks=64):
    """Полоса, центр которой едет от f_lo к f_hi (свист ветра)."""
    out = np.zeros_like(x)
    n = len(x)
    edges = np.linspace(0, n, blocks + 1).astype(int)
    win = np.hanning(2 * (edges[1] - edges[0]) + 2)
    for i in range(blocks):
        a = max(0, edges[i] - (edges[1] - edges[0]))
        b = min(n, edges[i + 1] + (edges[1] - edges[0]))
        k = i / max(1, blocks - 1)
        fc = f_lo * (f_hi / f_lo) ** k
        seg = band(x[a:b], fc * (1 - width / 2), fc * (1 + width))
        w = np.hanning(b - a)
        out[a:b] += seg * w
    return out


def env(t, attack, decay, power=1.0):
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.clip(1 - (t - attack) / max(decay, 1e-4), 0, 1) ** power
    return np.where(t < attack, a, d)


def thump(dur, f0, f1, decay):
    t = t_axis(dur)
    f = f1 + (f0 - f1) * np.exp(-t * 18)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / decay)


def reverb(x, length=0.8, mix=0.25):
    ir_t = t_axis(length)
    ir = band(noise(length), 150, 4000) * np.exp(-ir_t * 6.0)
    ir /= np.sqrt((ir ** 2).sum())
    wet = np.convolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 2.5


def finish(name, x, gain_db=-1.5):
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * 1.5) / np.tanh(1.5)
    x *= 10 ** (gain_db / 20)
    fade = min(len(x), int(SR * 0.03))
    x[-fade:] *= np.linspace(1, 0, fade)
    wav = OUT / f"{name}.wav"
    with wave.open(str(wav), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32000).astype("<i2").tobytes())
    ogg = OUT / f"{name}.ogg"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "6", str(ogg)],
                   check=True)
    wav.unlink()
    print(name, round(len(x) / SR, 2), "s")


def dash_front():
    d = 0.9
    t = t_axis(d)
    hit = np.zeros_like(t)
    th = thump(0.35, 140, 45, 0.08)
    hit[:len(th)] += th * 0.9
    grit = band(noise(d), 300, 2500) * env(t, 0.005, 0.12, 2) * 0.6
    wind = moving_band(noise(d), 600, 2400, 0.8) * env(t, 0.03, 0.55, 1.5) * 1.4
    skid = band(noise(d), 1500, 6000) * np.exp(-((t - 0.62) / 0.12) ** 2) * 0.55
    finish("move_dash_front", reverb(hit + grit + wind + skid, 0.6, 0.2))


def dash_side():
    d = 0.6
    t = t_axis(d)
    wind = moving_band(noise(d), 900, 3200, 0.7) * env(t, 0.02, 0.4, 1.8) * 1.5
    pop = band(noise(d), 200, 1200) * env(t, 0.002, 0.07, 2) * 0.8
    finish("move_dash_side", reverb(wind + pop, 0.5, 0.18))


def crouch():
    d = 0.8
    t = t_axis(d)
    hum = np.sin(2 * np.pi * (50 + 40 * t / d) * t) * env(t, 0.6, 0.2) * 0.6
    rustle = band(noise(d), 200, 1500) * env(t, 0.5, 0.3) * 0.5
    finish("move_crouch", hum + rustle, -6.0)


def takeoff():
    d = 1.8
    t = t_axis(d)
    hit = np.zeros_like(t)
    th = thump(0.6, 120, 38, 0.15)
    hit[:len(th)] += th
    dust = band(noise(d), 200, 3000) * env(t, 0.004, 0.25, 2) * 0.7
    swirl = moving_band(noise(d), 300, 2800, 0.9) * env(t, 0.08, 1.4, 1.3)
    swirl *= 0.75 + 0.25 * np.sin(2 * np.pi * 6.0 * t)
    sparkle = band(noise(d), 5000, 12000) * env(t, 0.3, 1.2) * (RNG.random(len(t)) > 0.995) * 3.0
    finish("move_takeoff", reverb(hit + dust + swirl * 1.3 + sparkle, 1.0, 0.25))


def boost():
    d = 1.0
    t = t_axis(d)
    gust = moving_band(noise(d), 400, 1800, 0.9) * env(t, 0.06, 0.85, 1.4) * 1.5
    low = band(noise(d), 60, 300) * env(t, 0.05, 0.6, 1.5) * 0.8
    finish("move_boost", reverb(gust + low, 0.6, 0.2), -2.5)


def step():
    d = 0.25
    t = t_axis(d)
    th = thump(d, 110, 50, 0.04) * 0.8
    grit = band(noise(d), 400, 3500) * env(t, 0.002, 0.09, 2) * 0.6
    finish("move_step", th + grit, -5.0)


def land():
    d = 0.7
    t = t_axis(d)
    th = thump(d, 100, 40, 0.12)
    dust = band(noise(d), 200, 2500) * env(t, 0.003, 0.3, 2) * 0.6
    finish("move_land", reverb(th + dust, 0.6, 0.2), -2.0)


dash_front()
dash_side()
crouch()
takeoff()
boost()
step()
land()
