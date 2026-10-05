#!/usr/bin/env python3
"""Звуки обычного Красного (свой синтез, моно — звучат из точки в мире).

Результат: src/main/resources/assets/jujutsu_neon/sounds/red/*.ogg
  red_cast — каст: нарастающий гул энергии с треском, на 0.65 с (R_FIRE) хлёсткий щелчок
             выстрела и уходящий вдаль свист шарика. Без фона и разрушений.
  red_boom — попадание: резкий треск, глубокий удар с провалом тона, рёв огня,
             осыпающиеся обломки и долгий низкий раскат.
"""
import subprocess
import wave
from pathlib import Path

import numpy as np

SR = 48000
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/sounds/red"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(23)


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
        seg = band(x[a:b], fc * (1 - width / 2), fc * (1 + width))
        out[a:b] += seg * np.hanning(b - a)
    return out


def env(t, attack, decay, power=1.0):
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = np.clip(1 - (t - attack) / max(decay, 1e-4), 0, 1) ** power
    return np.where(t < attack, a, d)


def sweep(t, f0, f1, rate):
    f = f1 + (f0 - f1) * np.exp(-t * rate)
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def crackle(dur, density, lo, hi, seed):
    rng = np.random.default_rng(seed)
    n = int(SR * dur)
    x = np.zeros(n)
    hits = rng.random(n) < density / SR
    x[hits] = rng.standard_normal(hits.sum()) * (0.4 + rng.random(hits.sum()))
    # каждый щелчок — короткий затухающий шорох
    k = np.exp(-np.arange(int(SR * 0.006)) / (SR * 0.0012))
    x = np.convolve(x, k)[:n]
    return band(x, lo, hi)


def reverb(x, length=1.2, mix=0.25, lo=120, hi=5000, decay=4.5):
    x = x.copy()
    k = int(SR * 0.25)
    x[-k:] *= np.linspace(1, 0, k) ** 2  # сухой сигнал гаснет плавно — без щелчка в хвосте
    ir_t = t_axis(length)
    ir = band(noise(length), lo, hi) * np.exp(-ir_t * decay)
    ir /= np.sqrt((ir ** 2).sum())
    wet = np.convolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * 2.5


def finish(name, x, gain_db=-1.0, drive=1.6):
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
    ogg = OUT / f"{name}.ogg"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "7", str(ogg)],
                   check=True)
    wav.unlink()
    print(name, round(len(x) / SR, 2), "s")


def red_cast():
    d = 1.9
    t = t_axis(d)
    fire = 0.65  # R_FIRE = 13 тиков

    # 1) Сгусток энергии собирается: гул с биениями, тон ползёт вверх до выстрела.
    pre = np.clip(t / fire, 0, 1)
    f_hum = 70 + 130 * pre ** 1.6
    hum = (np.sin(2 * np.pi * np.cumsum(f_hum) / SR) * 0.8
           + np.sin(2 * np.pi * np.cumsum(f_hum * 2.01) / SR) * 0.35
           + np.sin(2 * np.pi * np.cumsum(f_hum * 3.02) / SR) * 0.15)
    hum_env = np.where(t < fire, pre ** 1.4, np.exp(-(t - fire) / 0.07))
    hum *= hum_env * (0.8 + 0.2 * np.sin(2 * np.pi * 17 * t))

    # электрический треск, густеет к выстрелу
    crk = crackle(d, 260, 1800, 9000, 5) * np.where(t < fire, pre ** 2, np.exp(-(t - fire) / 0.12)) * 1.6
    # шипение энергии
    sizzle = band(noise(d), 3000, 11000) * np.where(t < fire, pre ** 2.5, np.exp(-(t - fire) / 0.05)) * 0.35

    # 2) Выстрел: хлёсткий щелчок + короткий плотный удар.
    tf = np.clip(t - fire, 0, None)
    on = (t >= fire).astype(float)
    snap = band(noise(d), 1200, 9000) * np.exp(-tf / 0.010) * on * 2.4
    punch = sweep(tf, 260, 55, 30) * np.exp(-tf / 0.09) * on * 1.4
    body = band(noise(d), 150, 1400) * np.exp(-tf / 0.06) * on * 0.9

    # 3) Шарик уходит: свист с падением тона (доплер) и затухающий гул.
    whoosh = moving_band(noise(d), 2600, 500, 0.7) * np.exp(-tf / 0.38) * env(tf, 0.02, 1.2) * on * 1.3
    tail_tone = sweep(tf, 900, 240, 4.5) * np.exp(-tf / 0.35) * on * 0.30

    mix = hum * 0.9 + crk + sizzle + snap + punch + body + whoosh + tail_tone
    finish("red_cast", reverb(mix, 0.9, 0.18, 150, 6000, 5.5), -1.5)


def red_boom():
    d = 4.6
    t = t_axis(d)

    # треск фронта взрыва
    crack = band(noise(d), 900, 12000) * np.exp(-t / 0.012) * 2.2
    crack += band(noise(d), 300, 3000) * np.exp(-t / 0.035) * 1.2

    # глубокий удар с провалом тона
    sub = sweep(t, 95, 26, 9) * np.exp(-t / 0.75) * 2.2
    sub += sweep(t, 160, 40, 14) * np.exp(-t / 0.25) * 0.9

    # тело взрыва — плотный низко-средний шум
    body = band(noise(d), 50, 900) * env(t, 0.004, 1.6, 2.2) * 1.8

    # рёв огня: медленно «дышащая» полоса, нарастает чуть позже удара
    roar = band(noise(d), 180, 2600) * env(t, 0.08, 2.8, 1.6)
    roar *= 0.7 + 0.3 * band(np.abs(noise(d)), 0, 6) * 4
    roar = roar * 0.9

    # красная энергия схлопывается: падающий звенящий тон
    energy = (sweep(t, 1400, 140, 3.2) * 0.5 + sweep(t, 2100, 210, 3.0) * 0.25) * np.exp(-t / 0.55)

    # обломки: редкий треск, осыпается по нарастающей задержке
    deb_env = np.clip((t - 0.15) / 0.3, 0, 1) * np.exp(-np.clip(t - 0.4, 0, None) / 1.3)
    debris = crackle(d, 140, 700, 6000, 9) * deb_env * 1.6
    pebbles = crackle(d, 60, 2500, 9000, 17) * np.clip((t - 0.6) / 0.4, 0, 1) * np.exp(-np.clip(t - 1.0, 0, None) / 1.2)

    # долгий раскат
    rumble = band(noise(d), 22, 140) * env(t, 0.05, 4.4, 1.3) * 1.6

    mix = crack + sub + body + roar + energy + debris + pebbles + rumble
    finish("red_boom", reverb(mix, 2.2, 0.28, 60, 3000, 2.2), -0.5, 2.0)


red_cast()
red_boom()
