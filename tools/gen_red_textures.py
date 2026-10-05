#!/usr/bin/env python3
"""Текстуры эффектов Красного и Максимального Красного (белые/серые — цвет задаёт вершина).

  red_energy.png — бесшовная закрученная энергия для оболочек шара (512×256)
  red_aura.png   — огромное кольцо-аура с рваным дымным краем (Макс. Красный)
  red_shock.png  — тонкая ударная волна с дымкой
  red_glow.png   — мягкое свечение (ореол шара, свечение на земле)
  red_heat.png   — концентрические «волны жара» — искажение воздуха вокруг летящего шара
  red_streak.png — лента энергии: яркая середина, мягкие края, бегущие волокна (ленты, луч)
"""
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(7)


def save(rgb, a, name):
    rgb = np.clip(rgb, 0, 1)
    a = np.clip(a, 0, 1)
    img = np.dstack([rgb, a]) if rgb.ndim == 3 else np.dstack([rgb, rgb, rgb, a])
    Image.fromarray((img * 255).astype(np.uint8), "RGBA").save(OUT / name, optimize=True)


def tile_noise(w, h, freq, octaves=4, seed=0):
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp = 1.0
    total = 0.0
    for o in range(octaves):
        fx, fy = freq * 2 ** o, max(1, freq * 2 ** o // 2)
        grid = rng.random((fy + 1, fx + 1))
        grid[-1, :] = grid[0, :]
        grid[:, -1] = grid[:, 0]
        ys = np.linspace(0, fy, h, endpoint=False)
        xs = np.linspace(0, fx, w, endpoint=False)
        y0 = ys.astype(int)
        x0 = xs.astype(int)
        ty = (ys - y0)[:, None]
        tx = (xs - x0)[None, :]
        ty = ty * ty * (3 - 2 * ty)
        tx = tx * tx * (3 - 2 * tx)
        a = grid[y0][:, x0]
        b = grid[y0][:, x0 + 1]
        c = grid[y0 + 1][:, x0]
        d = grid[y0 + 1][:, x0 + 1]
        v = a * (1 - tx) * (1 - ty) + b * tx * (1 - ty) + c * (1 - tx) * ty + d * tx * ty
        out += v * amp
        total += amp
        amp *= 0.5
    return out / total


def polar(s):
    y, x = np.mgrid[0:s, 0:s]
    cx = (s - 1) / 2
    dx, dy = (x - cx) / cx, (y - cx) / cx
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


def energy():
    w, h = 512, 256
    n1 = tile_noise(w, h, 6, 5, 1)
    n2 = tile_noise(w, h, 12, 4, 2)
    y = np.linspace(0, 1, h)[:, None]
    x = np.linspace(0, 1, w)[None, :]
    swirl = np.sin((x * 6 + y * 3 + n1 * 2.2) * np.pi * 2) * 0.5 + 0.5
    fil = np.clip((swirl - 0.55) * 3.0, 0, 1) ** 1.5
    white = np.clip(fil * 0.9 + (n2 - 0.5) * 0.6, 0, 1)
    a = np.clip(0.18 + fil * 0.75 + (n1 - 0.5) * 0.4, 0, 1)
    rgb = np.dstack([np.ones_like(white), 0.55 + 0.45 * white, 0.50 + 0.50 * white])
    save(rgb, a, "red_energy.png")


def aura():
    s = 512
    r, ang = polar(s)
    n = tile_noise(s, s, 8, 5, 3)
    edge = 0.78 + (n - 0.5) * 0.18
    ring = np.exp(-((r - edge) / 0.10) ** 2)
    inner = np.clip((edge - r) / edge, 0, 1) ** 2.5 * 0.35
    smoke = np.clip((n - 0.35) * 1.6, 0, 1)
    a = np.clip(ring * (0.55 + 0.45 * smoke) + inner * smoke, 0, 1) * (r < 1.0)
    save(np.ones((s, s)), a, "red_aura.png")


def shock():
    s = 512
    r, ang = polar(s)
    n = tile_noise(s, s, 10, 4, 4)
    ring = np.exp(-((r - 0.86) / 0.025) ** 2)
    haze = np.exp(-((r - 0.80) / 0.12) ** 2) * (0.35 + 0.4 * n)
    a = np.clip(ring + haze, 0, 1) * (r < 1.0)
    save(np.ones((s, s)), a, "red_shock.png")


def glow():
    s = 256
    r, _ = polar(s)
    a = np.clip(1.0 - r, 0, 1) ** 2.2
    core = np.clip(1.0 - r * 3.0, 0, 1)
    white = np.clip(core, 0, 1)
    save(np.dstack([np.ones_like(r), 0.7 + 0.3 * white, 0.7 + 0.3 * white]), a, "red_glow.png")


def heat():
    s = 256
    r, ang = polar(s)
    waves = (np.sin(r * 30.0) * 0.5 + 0.5) ** 3
    a = waves * np.clip(1.0 - r, 0, 1) ** 1.2 * np.clip(r * 4.0, 0, 1) * 0.6
    save(np.ones((s, s)), a, "red_heat.png")


def streak():
    w, h = 64, 256
    u = np.linspace(-1, 1, w)[None, :]
    v = np.linspace(0, 1, h)[:, None]
    n = tile_noise(w * 4, h, 4, 4, 5)[:, ::4]
    profile = np.exp(-(u / 0.42) ** 2)
    core = np.exp(-(u / 0.12) ** 2)
    fibers = 0.65 + 0.35 * np.sin((u * 7 + n * 3) * np.pi)
    a = np.clip(profile * fibers * (0.7 + 0.3 * n) + core, 0, 1)
    white = np.broadcast_to(np.clip(core * 1.2, 0, 1), a.shape)
    save(np.dstack([np.ones_like(a), 0.6 + 0.4 * white, 0.6 + 0.4 * white]), a, "red_streak.png")


energy()
aura()
shock()
glow()
heat()
streak()
print("ok")
