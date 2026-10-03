#!/usr/bin/env python3
"""Текстуры кат-сцены Максимального Фиолетового.

Запускается вручную, результат (PNG) лежит в репозитории:
src/main/resources/assets/jujutsu_neon/textures/gui/max_purple_*.png
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)


def value_noise(w, h, scale, rng, octaves=5, wrap_x=False):
    """Многооктавный value noise 0..1."""
    total = np.zeros((h, w), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        cw = max(2, int(scale * (2 ** o)))
        ch = max(2, int(scale * h / w * (2 ** o)))
        grid = rng.random((ch + 1, cw + 1)).astype(np.float32)
        if wrap_x:
            grid[:, -1] = grid[:, 0]
        img = Image.fromarray((grid * 255).astype(np.uint8), "L").resize((w, h), Image.BICUBIC)
        total += np.asarray(img, np.float32) / 255.0 * amp
        norm += amp
        amp *= 0.5
    return total / norm


# ---------------------------------------------------------------- impact frame
def make_impact():
    W, H = 1280, 720
    rng = random.Random(7)
    img = Image.new("RGB", (W, H), (0, 0, 0))
    px = np.zeros((H, W, 3), np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy = W * 0.70, H * 0.52
    # Тёмно-малиновый фон: светлее у центра вспышки, к левому низу почти чёрный.
    d = np.sqrt(((xx - cx) / W) ** 2 + ((yy - cy) / H) ** 2)
    k = np.clip(1.0 - d * 1.25, 0, 1)
    shade = np.clip(1.0 - (np.clip((W * 0.35 - xx) / W, 0, 1) * 1.6 + np.clip((yy - H * 0.55) / H, 0, 1) * 1.2), 0.0, 1.0)
    base = 0.25 + 0.75 * shade
    px[..., 0] = (95 + 125 * k) * base
    px[..., 1] = (0 + 8 * k) * base
    px[..., 2] = (20 + 40 * k) * base
    img = Image.fromarray(np.clip(px, 0, 255).astype(np.uint8))

    draw = ImageDraw.Draw(img, "RGBA")

    # Манговая штриховка: пучки тонких белых линий, направленных от центра.
    for _ in range(46):
        ang = rng.uniform(0, math.tau)
        dist = rng.uniform(150, 620)
        bx = cx + math.cos(ang) * dist
        by = cy + math.sin(ang) * dist * 0.62
        n = rng.randint(14, 34)
        spread = rng.uniform(0.25, 0.6)
        for i in range(n):
            a = ang + (i / n - 0.5) * spread
            l0 = rng.uniform(10, 40)
            l1 = l0 + rng.uniform(40, 140)
            x0 = bx + math.cos(a) * l0
            y0 = by + math.sin(a) * l0
            x1 = bx + math.cos(a) * l1
            y1 = by + math.sin(a) * l1
            draw.line((x0, y0, x1, y1), fill=(255, 225, 235, rng.randint(110, 200)), width=rng.choice((1, 1, 2)))

    # Чёрные мазки «кистью».
    for _ in range(14):
        x = rng.uniform(0, W * 0.6)
        y = rng.uniform(H * 0.3, H)
        pts = []
        a = rng.uniform(-1.2, -0.3)
        for s in range(9):
            pts.append((x, y))
            x += math.cos(a) * rng.uniform(25, 55)
            y += math.sin(a) * rng.uniform(25, 55)
            a += rng.uniform(-0.35, 0.35)
        draw.line(pts, fill=(0, 0, 0, 235), width=rng.randint(10, 26), joint="curve")

    # Белые «осколки»-клинья.
    for _ in range(22):
        x = rng.uniform(W * 0.25, W * 0.68)
        y = rng.uniform(H * 0.15, H * 0.95)
        a = rng.uniform(-math.pi * 0.9, -math.pi * 0.1)
        L = rng.uniform(40, 120)
        w = rng.uniform(5, 14)
        tip = (x + math.cos(a) * L, y + math.sin(a) * L)
        nx, ny = -math.sin(a) * w, math.cos(a) * w
        draw.polygon([(x + nx, y + ny), (x - nx, y - ny), tip], fill=(255, 255, 255, 235))

    # Центральная четырёхлучевая звезда (астроида), лучи до краёв кадра.
    star = []
    for i in range(720):
        t = i / 720 * math.tau
        c, s = math.cos(t), math.sin(t)
        x = cx + math.copysign(abs(c) ** 7.0, c) * W * 0.85
        y = cy + math.copysign(abs(s) ** 7.0, s) * H * 0.95
        star.append((x, y))
    draw.polygon(star, fill=(255, 255, 255, 255))
    # Тонкая горизонтальная трещина через весь кадр.
    draw.line((0, cy - 6, W, cy - 2), fill=(255, 255, 255, 255), width=5)

    # Белые изломанные трещины-молнии.
    def crack(x, y, a, length, width):
        pts = [(x, y)]
        for _ in range(int(length / 18)):
            a += rng.uniform(-0.6, 0.6)
            x += math.cos(a) * 18
            y += math.sin(a) * 18
            pts.append((x, y))
        draw.line(pts, fill=(255, 255, 255, 255), width=width, joint="curve")
        return pts

    for a0, L in ((-2.5, 520), (-0.9, 420), (0.7, 480), (2.6, 560), (-1.9, 380), (1.9, 360)):
        pts = crack(cx + math.cos(a0) * 120, cy + math.sin(a0) * 80, a0, L, rng.randint(7, 12))
        for p in pts[3::5]:
            crack(p[0], p[1], a0 + rng.uniform(-1.2, 1.2), rng.uniform(60, 160), 3)

    img = img.filter(ImageFilter.GaussianBlur(0.6))
    img.save(OUT / "max_purple_impact.png", optimize=True)


# ---------------------------------------------------------------- cosmos
def make_cosmos():
    S = 1024
    rng = np.random.default_rng(11)
    n1 = value_noise(S, S, 3.0, rng, 6)
    n2 = value_noise(S, S, 1.6, rng, 5)
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    # Слева фиолетово-синий, к центру/справа малиновый и розово-белый.
    t = np.clip(xx * 0.85 + (n1 - 0.5) * 0.55, 0, 1)
    deep = np.array([60, 20, 200], np.float32)
    mid = np.array([205, 20, 220], np.float32)
    hot = np.array([255, 120, 245], np.float32)
    col = np.where(t[..., None] < 0.5,
                   deep + (mid - deep) * (t[..., None] / 0.5),
                   mid + (hot - mid) * ((t[..., None] - 0.5) / 0.5))
    cloud = np.clip((n2 - 0.35) * 1.8, 0, 1)[..., None]
    col = col * (0.55 + 0.45 * cloud) + np.array([255, 200, 255], np.float32) * (cloud ** 3) * 0.35
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8))

    # Звёзды и искры с ореолом.
    glow = Image.new("RGB", (S, S), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    sharp = Image.new("RGB", (S, S), (0, 0, 0))
    sd = ImageDraw.Draw(sharp)
    r = random.Random(5)
    for _ in range(2600):
        x, y = r.uniform(0, S), r.uniform(0, S)
        size = r.choice((1, 1, 1, 1, 1, 2, 2, 2, 3, 4, 5))
        sd.ellipse((x - size / 2, y - size / 2, x + size / 2, y + size / 2), fill=(255, 245, 255))
        if size >= 3:
            gd.ellipse((x - size * 2, y - size * 2, x + size * 2, y + size * 2), fill=(255, 150, 250))
    glow = glow.filter(ImageFilter.GaussianBlur(5))
    img = ImageChops.add(img, glow)
    img = ImageChops.add(img, sharp)
    img.save(OUT / "max_purple_cosmos.png", optimize=True)


# ---------------------------------------------------------------- bloom
def make_bloom():
    S = 512
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    d = np.sqrt((xx - S / 2) ** 2 + (yy - S / 2) ** 2) / (S / 2)
    a = np.clip(1.0 - d, 0, 1) ** 1.6
    rgb = np.zeros((S, S, 4), np.float32)
    rgb[..., 0] = 255
    rgb[..., 1] = 225 + 30 * a
    rgb[..., 2] = 255
    rgb[..., 3] = a * 255
    Image.fromarray(rgb.astype(np.uint8), "RGBA").save(OUT / "max_purple_bloom.png", optimize=True)


# ---------------------------------------------------------------- orbs
def make_orb(name, dark, main, vein, seed):
    W, H = 512, 256
    rng = np.random.default_rng(seed)
    n = value_noise(W, H, 6.0, rng, 6, wrap_x=True)
    v = value_noise(W, H, 5.0, rng, 5, wrap_x=True)
    dark = np.array(dark, np.float32)
    main = np.array(main, np.float32)
    vein = np.array(vein, np.float32)
    base = main + (dark - main) * np.clip((n[..., None] - 0.55) * 4.0, 0, 1)
    # Тонкие светлые «прожилки» вдоль изолиний шума.
    lines = np.clip(1.0 - np.abs(v - 0.5) * 22.0, 0, 1)[..., None]
    col = base + (vein - base) * lines * 0.85
    Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(OUT / f"max_purple_{name}_orb.png", optimize=True)


make_impact()
make_cosmos()
make_bloom()
make_orb("red", (25, 0, 0), (225, 10, 18), (255, 90, 90), 3)
make_orb("blue", (0, 8, 70), (10, 70, 230), (90, 235, 255), 4)
print("ok", sorted(p.name for p in OUT.glob("max_purple_*.png")))
