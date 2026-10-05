#!/usr/bin/env python3
"""Звуки обычного Синего (свой синтез, моно — чтобы звучали из точки в мире).

Результат: src/main/resources/assets/jujutsu_neon/sounds/lapse/*.ogg
  lapse_cast    — треск электричества, нарастающий гул (вспыхивает Синий)
  lapse_burst   — глухой «вумп» синего взрыва с искрящимся хвостом
  lapse_pull    — обратный свист: притягивание
  lapse_kick    — взмах и удар ногой вверх
  lapse_blink   — короткий щелчок телепорта (без стекла)
  lapse_slowmo  — замедление: низкий провал звука, «стук сердца»
  lapse_contact — тяжёлый замедленный удар ногой
  lapse_slam    — удар о землю: взрыв, грохот и осыпь обломков
  lapse_land    — мягкое приземление
"""
import subprocess
import wave
from pathlib import Path

import numpy as np

SR = 48000
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/sounds/lapse"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(7)


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur):
    return RNG.standard_normal(int(SR * dur)).astype(np.float64)


def band(x, lo, hi):
    """Полосовой фильтр через БПФ (мягкие края)."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = np.fft.rfft(x)
    lo_w = 1 / (1 + (lo / np.maximum(f, 1)) ** 4) if lo > 0 else 1
    hi_w = 1 / (1 + (f / hi) ** 4) if hi > 0 else 1
    return np.fft.irfft(spec * lo_w * hi_w, n)


def sweep(t, f0, f1, curve=1.0):
    k = (t / t[-1]) ** curve
    f = f0 + (f1 - f0) * k
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def env(t, attack, decay, power=1.0):
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.clip(1 - (t - attack) / max(decay, 1e-4), 0, 1) ** power
    return np.where(t < attack, a, d)


def reverb(x, length=1.2, mix=0.35, damp=3500):
    ir_t = t_axis(length)
    ir = band(noise(length), 120, damp) * np.exp(-ir_t * 4.5)
    ir /= np.sqrt((ir ** 2).sum())
    wet = np.convolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 3.0


def crackle(dur, density, lo=1800, hi=9000):
    x = np.zeros(int(SR * dur))
    for _ in range(int(density * dur)):
        p = RNG.integers(0, len(x) - 400)
        ln = RNG.integers(30, 380)
        x[p:p + ln] += RNG.standard_normal(ln) * np.exp(-np.arange(ln) / (ln / 4)) * RNG.uniform(0.3, 1)
    return band(x, lo, hi)


def finish(name, x, gain_db=-1.0):
    x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.tanh(x * 1.6) / np.tanh(1.6)
    x *= 10 ** (gain_db / 20)
    fade = min(len(x), int(SR * 0.02))
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
    print(name, round(len(x) / SR, 2), "s", ogg.stat().st_size, "bytes")


def cast():
    d = 0.75
    t = t_axis(d)
    hum = sweep(t, 140, 420, 0.7) * 0.5 + sweep(t, 280, 840, 0.7) * 0.25
    hum *= env(t, 0.35, 0.4)
    cr = crackle(d, 160) * np.clip(t / 0.5, 0, 1) * env(t, 0.5, 0.25)
    air = band(noise(d), 3000, 12000) * 0.25 * env(t, 0.45, 0.3)
    finish("lapse_cast", reverb(hum + cr * 0.8 + air, 0.6, 0.25))


def burst():
    d = 0.8
    t = t_axis(d)
    thump = sweep(t, 110, 38, 0.35) * env(t, 0.006, 0.45, 1.6)
    body = band(noise(d), 80, 900) * env(t, 0.004, 0.35, 2.0) * 0.8
    sparkle = crackle(d, 90, 3500, 14000) * env(t, 0.02, 0.7, 1.4) * 0.6
    shimmer = sweep(t, 1800, 2600) * env(t, 0.05, 0.6, 2.0) * 0.12
    finish("lapse_burst", reverb(thump * 1.4 + body + sparkle + shimmer, 0.9, 0.3))


def pull():
    d = 0.5
    t = t_axis(d)
    k = t / d
    w = band(noise(d), 600, 7000) * (k ** 2.2) * np.clip((d - t) / 0.03, 0, 1)
    tone = sweep(t, 200, 900, 2.0) * (k ** 2.5) * 0.35
    finish("lapse_pull", w + tone, -2.0)


def kick():
    d = 0.7
    t = t_axis(d)
    whoosh = band(noise(d), 500, 5000) * np.exp(-((t - 0.10) / 0.06) ** 2)
    hit_t = np.clip(t - 0.12, 0, None)
    hit = sweep(t, 160, 60, 0.4) * np.where(t > 0.12, np.exp(-hit_t * 14), 0)
    crack = band(noise(d), 1500, 9000) * np.where(t > 0.12, np.exp(-hit_t * 40), 0)
    air = band(noise(d), 2000, 9000) * np.where(t > 0.12, np.exp(-hit_t * 6), 0) * 0.25
    finish("lapse_kick", reverb(whoosh * 0.9 + hit * 1.3 + crack * 0.7 + air, 0.5, 0.2))


def blink():
    d = 0.3
    t = t_axis(d)
    zip_ = sweep(t, 2400, 500, 0.6) * env(t, 0.004, 0.22, 2.0) * 0.6
    snap = band(noise(d), 2000, 12000) * env(t, 0.001, 0.05, 2.0)
    finish("lapse_blink", zip_ + snap, -3.0)


def slowmo():
    d = 1.8
    t = t_axis(d)
    drop = sweep(t, 420, 55, 0.5) * env(t, 0.02, 1.6, 1.2) * 0.6
    sub = np.sin(2 * np.pi * 42 * t) * env(t, 0.3, 1.4) * 0.5
    beats = np.zeros_like(t)
    for bt in (0.15, 0.95):
        tt = np.clip(t - bt, 0, None)
        beats += np.where(t > bt, np.sin(2 * np.pi * (55 - 20 * np.clip(tt / 0.2, 0, 1)) * tt) * np.exp(-tt * 12), 0)
    wind = band(noise(d), 200, 1200) * env(t, 0.4, 1.3) * 0.25
    finish("lapse_slowmo", reverb(drop + sub + beats * 1.2 + wind, 1.4, 0.4, 1800))


def contact():
    d = 1.1
    t = t_axis(d)
    boom = sweep(t, 90, 30, 0.3) * env(t, 0.004, 0.9, 1.4)
    body = band(noise(d), 60, 600) * env(t, 0.002, 0.45, 2.2) * 0.9
    ring = sweep(t, 700, 380) * env(t, 0.01, 0.8, 2.0) * 0.18
    finish("lapse_contact", reverb(boom * 1.5 + body + ring, 1.6, 0.45, 2200))


def slam():
    d = 1.9
    t = t_axis(d)
    boom = sweep(t, 75, 26, 0.3) * env(t, 0.003, 1.2, 1.3) * 1.6
    blast = band(noise(d), 50, 2500) * env(t, 0.002, 0.6, 2.0) * 1.1
    rubble = np.zeros_like(t)
    for _ in range(140):
        p = RNG.uniform(0.05, 1.5)
        ln = RNG.uniform(0.01, 0.05)
        i0, n = int(p * SR), int(ln * SR)
        if i0 + n >= len(t):
            continue
        rubble[i0:i0 + n] += RNG.standard_normal(n) * np.exp(-np.arange(n) / (n / 3)) * RNG.uniform(0.2, 1) * np.exp(-p * 1.8)
    rubble = band(rubble, 300, 6000) * 0.7
    finish("lapse_slam", reverb(boom + blast + rubble, 1.5, 0.35, 2500))


def land():
    d = 0.35
    t = t_axis(d)
    thud = sweep(t, 120, 50, 0.4) * env(t, 0.003, 0.25, 1.8)
    dust = band(noise(d), 300, 3000) * env(t, 0.003, 0.2, 2.0) * 0.4
    finish("lapse_land", thud + dust, -4.0)


cast()
burst()
pull()
kick()
blink()
slowmo()
contact()
slam()
land()
