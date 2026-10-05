#!/usr/bin/env python3
"""Звуки Бесконечности и обратной проклятой техники (свой синтез, моно — звучат из точки в мире).

Результат: src/main/resources/assets/jujutsu_neon/sounds/cursed/*.ogg
  infinity_on    — включение: глухой «вдох» пространства, поднимающийся воздух и хрустальный аккорд,
                   который медленно затихает, будто время вокруг замедлилось
  infinity_off   — выключение: аккорд стекает вниз, мягкий выдох
  infinity_block — удар остановлен Бесконечностью: стеклянный звон, звук «вязнет» и тонет, глухой толчок
  rct_heal       — обратная техника: два удара сердца, тёплый светлый аккорд нарастает, искры, дыхание
"""
import subprocess
import wave
from pathlib import Path

import numpy as np

SR = 48000
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/sounds/cursed"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(41)


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


def tone(t, f, detune=0.0):
    """Синус с лёгким хорусом (две копии с расстройкой)."""
    f = np.broadcast_to(f, t.shape)
    ph1 = 2 * np.pi * np.cumsum(f) / SR
    ph2 = 2 * np.pi * np.cumsum(f * (1 + detune)) / SR
    return 0.5 * (np.sin(ph1) + np.sin(ph2 + 1.3))


def thump(dur, f0, f1, decay, rate=18):
    t = t_axis(dur)
    f = f1 + (f0 - f1) * np.exp(-t * rate)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / decay)


def place(dst, src, at):
    i = int(at * SR)
    n = min(len(src), len(dst) - i)
    if n > 0:
        dst[i:i + n] += src[:n]


def reverb(x, length=1.6, mix=0.3, lo=200, hi=7000, decay=3.5):
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
    wav = OUT / f"{name}.wav"
    with wave.open(str(wav), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32000).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "7",
                    str(OUT / f"{name}.ogg")], check=True)
    wav.unlink()
    print(name, round(len(x) / SR, 2), "s")


def infinity_on():
    d = 2.4
    t = t_axis(d)
    x = np.zeros_like(t)
    # «вдох» пространства: тон тянется вверх из глубины
    rise = tone(t, 60 + 140 * (1 - np.exp(-t * 4)), 0.004) * env(t, 0.25, 0.9, 1.5) * 0.9
    place(x, thump(0.9, 110, 38, 0.22), 0.02)
    air = moving_band(noise(d), 250, 3200, 0.8) * env(t, 0.35, 1.4, 1.6) * 0.6
    # хрустальный аккорд — медленно раскрывается, частоты чуть плавают («время вязнет»)
    wob = 1 + 0.003 * np.sin(2 * np.pi * 0.9 * t)
    chord = sum(tone(t, f * wob, 0.002 + i * 0.0007) * g
                for i, (f, g) in enumerate([(988, 0.5), (1319, 0.38), (1976, 0.26), (2637, 0.15), (3951, 0.07)]))
    chord *= env(t, 0.45, 1.9, 1.8) * (0.85 + 0.15 * np.sin(2 * np.pi * 6.5 * t))
    shimmer = band(noise(d), 6000, 14000) * env(t, 0.5, 1.5, 2) * 0.12
    finish("infinity_on", reverb(rise + x * 1.1 + air + chord * 0.75 + shimmer, 2.2, 0.38, 300, 9000, 2.4))


def infinity_off():
    d = 1.5
    t = t_axis(d)
    fall = np.exp(-t * 2.2)
    chord = sum(tone(t, f * (0.55 + 0.45 * fall), 0.003) * g
                for f, g in [(1319, 0.45), (1976, 0.3), (2637, 0.16)])
    chord *= env(t, 0.03, 1.1, 1.6)
    breath = moving_band(noise(d), 2400, 300, 0.8) * env(t, 0.06, 1.0, 1.7) * 0.6
    x = np.zeros_like(t)
    place(x, thump(0.5, 90, 45, 0.12), 0.0)
    finish("infinity_off", reverb(chord * 0.7 + breath + x * 0.7, 1.4, 0.32, 300, 8000, 3.2), -3.0)


def infinity_block():
    d = 1.1
    t = t_axis(d)
    # стеклянный звон: негармоничные обертоны, быстрый спад
    ping = sum(np.sin(2 * np.pi * f * t + i) * np.exp(-t / dec) * g
               for i, (f, g, dec) in enumerate([(2217, 0.5, 0.22), (3341, 0.32, 0.14), (5120, 0.18, 0.08),
                                                (1480, 0.3, 0.3)]))
    # звук удара «вязнет»: тон проваливается и тонет
    sink = tone(t, 140 + 520 * np.exp(-t * 7), 0.01) * env(t, 0.005, 0.6, 2.0) * 0.8
    thud = band(noise(d), 60, 500) * env(t, 0.002, 0.18, 2.5) * 0.7
    air = band(noise(d), 1500, 6000) * env(t, 0.003, 0.25, 2.5) * 0.25
    finish("infinity_block", reverb(ping * 0.8 + sink + thud + air, 0.9, 0.26, 300, 9000, 4.5), -2.0)


def rct_heal():
    d = 2.8
    t = t_axis(d)
    x = np.zeros_like(t)
    # сердце: два двойных удара (lub-dub)
    for at, g in [(0.06, 1.0), (0.30, 0.7), (1.02, 0.8), (1.25, 0.55)]:
        place(x, thump(0.4, 95, 42, 0.09, 24) * g, at)
    # тёплый светлый аккорд (ля мажор) — нарастает, держится, тает
    chord = sum(tone(t, f, 0.003) * g for f, g in [(220, 0.45), (277.2, 0.35), (329.6, 0.35), (440, 0.3),
                                                    (659.3, 0.16), (880, 0.1)])
    chord *= env(t, 0.9, 1.8, 1.4) * (0.9 + 0.1 * np.sin(2 * np.pi * 4.5 * t))
    # поднимающийся «свет»: высокая гладкая нота скользит вверх
    lift = tone(t, 880 * (1 + 0.5 * np.clip(t / 1.6, 0, 1)), 0.002) * env(t, 1.0, 1.4, 1.6) * 0.18
    # искры
    rng = np.random.default_rng(5)
    sparks = np.zeros_like(t)
    for _ in range(26):
        at = rng.uniform(0.4, 2.1)
        f = rng.uniform(2500, 6000)
        tt = t_axis(0.25)
        place(sparks, np.sin(2 * np.pi * f * tt) * np.exp(-tt / 0.05) * rng.uniform(0.04, 0.1), at)
    breath = moving_band(noise(d), 300, 2200, 0.8) * env(t, 0.8, 1.6, 1.6) * 0.35
    finish("rct_heal", reverb(x * 1.2 + chord * 0.8 + lift + sparks + breath, 2.0, 0.34, 200, 8000, 2.6))


infinity_on()
infinity_off()
infinity_block()
rct_heal()
