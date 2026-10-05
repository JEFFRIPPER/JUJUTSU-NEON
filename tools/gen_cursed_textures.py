#!/usr/bin/env python3
"""Текстуры Бесконечности и обратной проклятой техники (белые — цвет задаёт вершина).

  cursed_glow.png   — мягкое круглое свечение с плотным ядром
  cursed_ripple.png — рябь пространства: тонкое яркое кольцо и две бледные волны внутри
  cursed_mote.png   — крошечная частица света с короткими лучиками
"""
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)


def save(a, name):
    a = np.clip(a, 0, 1)
    img = np.dstack([np.ones(a.shape + (3,)), a])
    Image.fromarray((img * 255).astype(np.uint8), "RGBA").save(OUT / name, optimize=True)


def polar(s):
    y, x = np.mgrid[0:s, 0:s]
    c = (s - 1) / 2
    dx, dy = (x - c) / c, (y - c) / c
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


def glow():
    r, _ = polar(256)
    a = np.exp(-(r / 0.42) ** 2) * 0.75 + np.exp(-(r / 0.12) ** 2) * 0.6
    save(a * np.clip((1 - r) / 0.15, 0, 1), "cursed_glow.png")


def ripple():
    r, ang = polar(512)
    wob = 0.004 * np.sin(ang * 9)
    a = (np.exp(-((r - 0.90 - wob) / 0.022) ** 2)
         + 0.35 * np.exp(-((r - 0.74 + wob) / 0.03) ** 2)
         + 0.18 * np.exp(-((r - 0.58) / 0.04) ** 2)
         + 0.10 * np.exp(-((r - 0.80) / 0.16) ** 2))
    save(a * (r < 1), "cursed_ripple.png")


def mote():
    s = 64
    y, x = np.mgrid[0:s, 0:s]
    c = (s - 1) / 2
    dx, dy = np.abs(x - c) / c, np.abs(y - c) / c
    r = np.sqrt(dx * dx + dy * dy)
    core = np.exp(-(r / 0.18) ** 2)
    rays = (np.exp(-dx / 0.04) * np.clip(1 - dy, 0, 1) ** 3 + np.exp(-dy / 0.04) * np.clip(1 - dx, 0, 1) ** 3) * 0.5
    save(np.clip(core + rays + np.exp(-(r / 0.45) ** 2) * 0.25, 0, 1), "cursed_mote.png")


glow()
ripple()
mote()
print("ok")
