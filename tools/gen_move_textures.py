#!/usr/bin/env python3
"""Текстуры эффектов движения (белые — цвет задаёт вершина).

  move_arc.png    — серп ветра: дуга с заострёнными концами и волокнами (вихрь взлёта/полёта)
  move_ring.png   — тонкое кольцо ударной волны по земле
  move_star.png   — четырёхлучевая искра-звёздочка
  move_streak.png — полоса ветра: яркая середина, волокна, мягкие края
  move_puff.png   — облачко пыли с рваным краем
"""
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)


def save(a, name, rgb=None):
    a = np.clip(a, 0, 1)
    if rgb is None:
        rgb = np.ones(a.shape + (3,))
    img = np.dstack([np.clip(rgb, 0, 1), a])
    Image.fromarray((img * 255).astype(np.uint8), "RGBA").save(OUT / name, optimize=True)


def noise(h, w, freq, seed):
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(4):
        fy, fx = max(2, freq * 2 ** o), max(2, freq * 2 ** o)
        g = rng.random((fy + 1, fx + 1))
        ys = np.linspace(0, fy, h, endpoint=False)
        xs = np.linspace(0, fx, w, endpoint=False)
        y0, x0 = ys.astype(int), xs.astype(int)
        ty, tx = (ys - y0)[:, None], (xs - x0)[None, :]
        ty, tx = ty * ty * (3 - 2 * ty), tx * tx * (3 - 2 * tx)
        v = (g[y0][:, x0] * (1 - tx) * (1 - ty) + g[y0][:, x0 + 1] * tx * (1 - ty)
             + g[y0 + 1][:, x0] * (1 - tx) * ty + g[y0 + 1][:, x0 + 1] * tx * ty)
        out += v * amp
        total += amp
        amp *= 0.5
    return out / total


def polar(s):
    y, x = np.mgrid[0:s, 0:s]
    c = (s - 1) / 2
    dx, dy = (x - c) / c, (y - c) / c
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


def arc():
    s = 512
    r, ang = polar(s)
    span = np.radians(150)
    u = (ang + np.pi) / (2 * np.pi)          # 0..1 по кругу
    a0 = (np.pi - span / 2)
    along = np.clip((ang + np.pi - a0) / span, 0, 1)
    inside = ((ang + np.pi) >= a0) & ((ang + np.pi) <= a0 + span)
    taper = np.sin(np.pi * along) ** 0.8
    width = 0.07 * taper + 0.005
    center = 0.80 + 0.04 * np.sin(along * np.pi)
    prof = np.exp(-((r - center) / np.maximum(width, 1e-3)) ** 2)
    n = noise(s, s, 6, 3)
    fibers = 0.6 + 0.4 * np.sin((r * 60 + n * 6))
    a = prof * fibers * inside * (0.4 + 0.6 * along)
    save(a, "move_arc.png")


def ring():
    s = 512
    r, _ = polar(s)
    a = np.exp(-((r - 0.88) / 0.035) ** 2) + np.exp(-((r - 0.80) / 0.10) ** 2) * 0.25
    save(a * (r < 1), "move_ring.png")


def star():
    s = 128
    y, x = np.mgrid[0:s, 0:s]
    c = (s - 1) / 2
    dx, dy = np.abs(x - c) / c, np.abs(y - c) / c
    rays = np.exp(-dx / 0.05) * np.clip(1 - dy, 0, 1) ** 2 + np.exp(-dy / 0.05) * np.clip(1 - dx, 0, 1) ** 2
    core = np.exp(-(dx ** 2 + dy ** 2) / 0.01)
    save(np.clip(rays + core, 0, 1), "move_star.png")


def streak():
    w, h = 64, 256
    u = np.linspace(-1, 1, w)[None, :]
    v = np.linspace(0, 1, h)[:, None]
    n = noise(h, w, 4, 5)
    prof = np.exp(-(u / 0.45) ** 2)
    core = np.exp(-(u / 0.12) ** 2)
    fibers = 0.7 + 0.3 * np.sin((u * 8 + n * 4) * np.pi)
    a = np.clip(prof * fibers * (0.6 + 0.4 * n) + core * 0.8, 0, 1) * np.sin(np.pi * v) ** 0.3
    save(a, "move_streak.png")


def puff():
    s = 256
    r, ang = polar(s)
    n = noise(s, s, 5, 9)
    edge = 0.75 + (n - 0.5) * 0.35
    a = np.clip((edge - r) / 0.35, 0, 1) ** 1.3 * (0.55 + 0.45 * n)
    save(a, "move_puff.png")


arc()
ring()
star()
streak()
puff()
print("ok")
