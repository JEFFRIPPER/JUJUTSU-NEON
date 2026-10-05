#!/usr/bin/env python3
"""Текстуры эффектов обычного Синего (свои, процедурные).

  lapse_arc.png      — дуга-молния вокруг цели (бело-голубая, рваная)
  lapse_burst.png    — синий взрыв-облако на цели
  lapse_crescent.png — чёрно-белый полумесяц ударной волны
  lapse_wind.png     — белый вихрь ветра (мазки по спирали)
  lapse_ring.png     — кольцо удара (касание ногой, удар о землю)
  lapse_cracks.png   — трещины на земле после удара
  lapse_smoke.png    — серая дымка (след падения, пыль)
  lapse_glint.png    — вытянутый белый блик (искры)
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(20261005)


def save(img, name):
    img.save(OUT / name, optimize=True)
    print(name, (OUT / name).stat().st_size)


def noise(s, scale, octaves=5):
    total = np.zeros((s, s), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        c = max(2, int(scale * (2 ** o)))
        grid = RNG.random((c + 1, c + 1)).astype(np.float32)
        img = Image.fromarray((grid * 255).astype(np.uint8), "L").resize((s, s), Image.BICUBIC)
        total += np.asarray(img, np.float32) / 255.0 * amp
        norm += amp
        amp *= 0.5
    return total / norm


def rgba(rgb_float, alpha_float):
    a = np.clip(alpha_float, 0, 1)
    rgb = np.clip(rgb_float, 0, 1)
    out = np.dstack([rgb, a])
    return Image.fromarray((out * 255).astype(np.uint8), "RGBA")


def polar(s):
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    dx, dy = (xx - s / 2) / (s / 2), (yy - s / 2) / (s / 2)
    return np.sqrt(dx * dx + dy * dy), np.arctan2(dy, dx)


# ---------------------------------------------------------------- дуга-молния
def arc():
    s = 512
    glow = Image.new("L", (s, s), 0)
    core = Image.new("L", (s, s), 0)
    dg, dc = ImageDraw.Draw(glow), ImageDraw.Draw(core)
    cx = cy = s / 2
    for strand in range(3):
        r0 = s * (0.40 + 0.012 * strand)
        start = math.radians(-200 + RNG.uniform(-12, 12))
        end = math.radians(25 + RNG.uniform(-12, 12))
        pts = []
        n = 90
        off = 0.0
        for i in range(n + 1):
            a = start + (end - start) * i / n
            off = off * 0.55 + RNG.uniform(-1, 1) * s * 0.012
            r = r0 + off
            pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
        w = 3 if strand == 0 else 2
        dg.line(pts, fill=150 if strand else 255, width=w + 10)
        dc.line(pts, fill=255 if strand == 0 else 200, width=w)
        # отростки наружу
        for _ in range(6):
            k = RNG.integers(5, n - 5)
            x, y = pts[k]
            a = start + (end - start) * k / n
            br = [(x, y)]
            for j in range(1, 6):
                rr = s * 0.02 * j
                br.append((x + math.cos(a + RNG.uniform(-0.6, 0.6)) * rr, y + math.sin(a + RNG.uniform(-0.6, 0.6)) * rr))
            dc.line(br, fill=210, width=1)
            dg.line(br, fill=110, width=6)
    glow = glow.filter(ImageFilter.GaussianBlur(9))
    g = np.asarray(glow, np.float32) / 255.0
    c = np.asarray(core.filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255.0
    a = np.clip(c + g * 0.85, 0, 1)
    white = np.clip(c * 1.2, 0, 1)
    rgb = np.dstack([0.35 + 0.65 * white, 0.80 + 0.20 * white, np.ones_like(white)])
    save(rgba(rgb, a), "lapse_arc.png")


# ---------------------------------------------------------------- синий взрыв
def burst():
    s = 512
    r, th = polar(s)
    n = noise(s, 3)
    streak = 0.5 + 0.5 * np.sin(th * 9 + n * 6)
    edge = 0.78 + 0.18 * (n - 0.5) + 0.06 * streak
    body = np.clip(1.0 - r / edge, 0, 1) ** 0.8
    core = np.clip(1.0 - r / 0.33, 0, 1) ** 1.6
    a = np.clip(body * (0.55 + 0.45 * n) + core, 0, 1)
    white = np.clip(core * 1.3 + body * 0.15, 0, 1)
    rgb = np.dstack([0.20 + 0.80 * white, 0.70 + 0.30 * white, np.ones_like(white)])
    save(rgba(rgb, a), "lapse_burst.png")


# ---------------------------------------------------------------- полумесяц
def crescent():
    s = 512
    r, th = polar(s)
    # серп: внешний край окружности, толщина сходит на нет к концам
    ang = (th + math.pi / 2) % (2 * math.pi) - math.pi  # 0 — сверху
    span = math.radians(85)
    t = np.clip(1.0 - np.abs(ang) / span, 0, 1)
    thick = 0.16 * np.sqrt(t)
    inner = 0.88 - thick
    band = (r <= 0.88) & (r >= inner) & (t > 0)
    rim = (r <= 0.88) & (r >= 0.88 - 0.035 * np.sqrt(t)) & (t > 0)
    jag = noise(s, 8) > 0.22
    a = np.where(band & jag, 1.0, 0.0).astype(np.float32)
    a = np.asarray(Image.fromarray((a * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2)), np.float32) / 255
    dark = np.where(rim, 1.0, 0.0)
    dark = np.asarray(Image.fromarray((dark * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0)), np.float32) / 255
    v = np.clip(1.0 - dark * 0.95, 0.04, 1)
    rgb = np.dstack([v, v, v])
    save(rgba(rgb, np.clip(a + dark * 0.9, 0, 1)), "lapse_crescent.png")


# ---------------------------------------------------------------- вихрь ветра
def wind():
    s = 512
    img = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(img)
    cx = cy = s / 2
    for k in range(26):
        r0 = s * RNG.uniform(0.18, 0.46)
        a0 = RNG.uniform(0, math.tau)
        length = RNG.uniform(1.2, 2.6)
        w = RNG.uniform(2, 9)
        val = int(RNG.uniform(120, 255))
        pts = []
        steps = 40
        for i in range(steps + 1):
            u = i / steps
            a = a0 + length * u
            rr = r0 * (1.0 - 0.18 * u)
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
        for i in range(steps):
            u = i / steps
            taper = math.sin(math.pi * u)
            d.line([pts[i], pts[i + 1]], fill=int(val * taper), width=max(1, int(w * taper + 1)))
    img = img.filter(ImageFilter.GaussianBlur(2.2))
    a = np.asarray(img, np.float32) / 255.0
    n = noise(s, 6)
    a = np.clip(a * (0.7 + 0.6 * n), 0, 1)
    g = 0.86 + 0.14 * n
    save(rgba(np.dstack([g, g, g * 1.02]), a), "lapse_wind.png")


# ---------------------------------------------------------------- кольцо удара
def ring():
    s = 256
    r, _ = polar(s)
    core = np.exp(-((r - 0.80) / 0.035) ** 2)
    glow = np.exp(-((r - 0.78) / 0.12) ** 2) * 0.55
    inner = np.clip(1.0 - r / 0.8, 0, 1) ** 3 * 0.25
    a = np.clip(core + glow + inner, 0, 1)
    white = np.clip(core * 1.2, 0, 1)
    rgb = np.dstack([0.75 + 0.25 * white, 0.90 + 0.10 * white, np.ones_like(white)])
    save(rgba(rgb, a), "lapse_ring.png")


# ---------------------------------------------------------------- трещины
def cracks():
    s = 512
    img = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(img)
    cx = cy = s / 2

    def branch(x, y, a, length, w, depth):
        steps = int(length / 9)
        for _ in range(steps):
            a += RNG.uniform(-0.35, 0.35)
            nx, ny = x + math.cos(a) * 9, y + math.sin(a) * 9
            d.line([(x, y), (nx, ny)], fill=255, width=max(1, int(w)))
            x, y = nx, ny
            w *= 0.97
            if depth < 3 and RNG.random() < 0.08:
                branch(x, y, a + RNG.choice([-1, 1]) * RNG.uniform(0.5, 1.1), length * 0.45, w * 0.7, depth + 1)

    # Трещины начинаются у края воронки (~0.55 радиуса), центр — дыра воронки.
    for k in range(14):
        a = k / 14 * math.tau + RNG.uniform(-0.2, 0.2)
        r0 = s * 0.27
        branch(cx + math.cos(a) * r0, cy + math.sin(a) * r0, a, RNG.uniform(70, 115), RNG.uniform(4, 7), 1)
    img = img.filter(ImageFilter.GaussianBlur(1.1))
    a = np.asarray(img, np.float32) / 255.0
    r, _ = polar(s)
    a *= np.clip(1.15 - r, 0, 1)
    save(rgba(np.dstack([np.full_like(a, 0.07), np.full_like(a, 0.07), np.full_like(a, 0.08)]), a * 0.9), "lapse_cracks.png")


# ---------------------------------------------------------------- дым и блик
def smoke():
    s = 256
    r, _ = polar(s)
    n = noise(s, 3)
    a = np.clip(1.0 - r / (0.7 + 0.3 * n), 0, 1) ** 1.5 * (0.55 + 0.45 * n)
    g = 0.78 + 0.18 * n
    save(rgba(np.dstack([g, g, g]), a), "lapse_smoke.png")


def glint():
    s = 128
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    dx, dy = (xx - s / 2) / (s * 0.12), (yy - s / 2) / (s * 0.46)
    d2 = dx * dx + dy * dy
    a = np.clip(np.exp(-d2 * 2.2) * 1.4, 0, 1)
    save(rgba(np.dstack([np.ones_like(a), np.ones_like(a), np.ones_like(a)]), a), "lapse_glint.png")


arc()
burst()
crescent()
wind()
ring()
cracks()
smoke()
glint()
