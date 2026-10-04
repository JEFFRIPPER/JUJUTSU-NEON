#!/usr/bin/env python3
"""Текстуры Расширения территории «Бесконечная пустота» (свои, процедурные).

  domain_void_sky.png   — небо пустоты изнутри (развёртка на сферу): тёмно-синий, дымка, звёзды
  domain_smoke.png      — полупрозрачные струи синего дыма (вращающиеся слои у горизонта)
  domain_blots.png      — белые чернильные кляксы (атлас 2x2) — висят вокруг, лежат на полу, растворяют мир
  domain_blackhole.png  — чёрная дыра со светящимся радужным кольцом
  domain_wings.png      — белые «крылья» туманности вокруг чёрной дыры
  domain_floor.png      — пол: тёмно-синяя гладь (бесшовная)
  domain_galaxy.png     — розово-фиолетово-синий космос для «гиперпрыжка» в катсцене
  domain_cracks.png     — трещины стекла на весь экран (разрушение территории)
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)


def save(img, name):
    img.save(OUT / name, optimize=True)
    print(name, (OUT / name).stat().st_size)


def value_noise(w, h, scale, rng, octaves=5, wrap_x=False):
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


def tileable_noise(s, scale, rng, octaves=5):
    """Бесшовный шум: сумма синусов со случайными фазами по целым частотам."""
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32) / s
    total = np.zeros((s, s), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        f = scale * (2 ** o)
        for _ in range(4):
            kx, ky = rng.integers(-f, f + 1, 2)
            ph = rng.random() * math.tau
            total += amp * np.sin(math.tau * (kx * xx + ky * yy) + ph)
            norm += amp
        amp *= 0.55
    return 0.5 + 0.5 * total / norm


def filaments(w, h, rng, count, wrap_x=True, thick=(1.0, 3.5), color=255):
    """Тонкие светлые струи дыма: изогнутые линии, размытые и наложенные слоями."""
    layer = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(layer)
    prng = random.Random(int(rng.integers(1 << 30)))
    for _ in range(count):
        x = prng.uniform(0, w)
        y = prng.uniform(h * 0.15, h * 0.85)
        ang = prng.uniform(-0.25, 0.25)
        length = prng.uniform(w * 0.15, w * 0.5)
        steps = int(length / 8)
        pts = []
        f1, f2 = prng.uniform(0.004, 0.012), prng.uniform(0.02, 0.05)
        p1, p2 = prng.uniform(0, 6.28), prng.uniform(0, 6.28)
        amp1, amp2 = prng.uniform(10, 60), prng.uniform(3, 14)
        for i in range(steps):
            t = i * 8
            px = x + math.cos(ang) * t
            py = y + math.sin(ang) * t + amp1 * math.sin(t * f1 + p1) + amp2 * math.sin(t * f2 + p2)
            pts.append((px, py))
        width = prng.uniform(*thick)
        c = prng.randint(int(color * 0.5), color)
        for shift in ((0, 0), (-w, 0), (w, 0)) if wrap_x else ((0, 0),):
            d.line([(px + shift[0], py) for px, py in pts], fill=c, width=max(1, int(width)))
    soft = layer.filter(ImageFilter.GaussianBlur(6))
    sharp = layer.filter(ImageFilter.GaussianBlur(1.2))
    return np.maximum(np.asarray(soft, np.float32) / 255.0 * 1.4, np.asarray(sharp, np.float32) / 255.0)


# ------------------------------------------------------------------ небо пустоты
def void_sky():
    w, h = 2048, 1024
    rng = np.random.default_rng(11)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    lat = (0.5 - yy / h) * math.pi  # +pi/2 вверху
    n = value_noise(w, h, 4.0, rng, 6, wrap_x=True)
    n2 = value_noise(w, h, 9.0, rng, 4, wrap_x=True)
    deep = np.array([3, 7, 22], np.float32)
    navy = np.array([14, 36, 92], np.float32)
    blue = np.array([40, 92, 170], np.float32)
    # к горизонту светлее, к зениту — почти чёрный космос
    horizon = np.exp(-(lat / 0.55) ** 2)
    col = deep + (navy - deep) * (0.35 + 0.65 * horizon)[..., None] * (0.6 + 0.6 * n)[..., None]
    col += (blue - col) * (np.clip(n2 - 0.55, 0, 1) * 1.6 * horizon)[..., None]
    fil = filaments(w, h, rng, 140, thick=(1.0, 4.0))
    fil *= (0.35 + 0.65 * horizon)
    col += np.array([150, 190, 240], np.float32) * fil[..., None] * 0.55
    # звёзды в тёмных местах
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    prng = random.Random(12)
    for _ in range(1400):
        x, y = prng.uniform(0, w), prng.uniform(0, h)
        b = prng.randint(140, 255)
        r = prng.choice((0.6, 0.8, 1.0, 1.3))
        d.ellipse((x - r, y - r, x + r, y + r), fill=(b, b, min(255, b + 10)))
    save(img, "domain_void_sky.png")


def smoke():
    w, h = 2048, 512
    rng = np.random.default_rng(21)
    fil = filaments(w, h, rng, 90, thick=(1.5, 6.0))
    n = value_noise(w, h, 5.0, rng, 4, wrap_x=True)
    yy = np.mgrid[0:h, 0:w][0].astype(np.float32)
    band = np.sin(np.clip(yy / h, 0, 1) * math.pi) ** 1.5
    a = np.clip(fil * (0.6 + 0.6 * n) * band, 0, 1)
    rgb = np.dstack([np.full_like(a, 175), np.full_like(a, 205), np.full_like(a, 245)])
    out = np.dstack([rgb, a * 235]).astype(np.uint8)
    save(Image.fromarray(out, "RGBA"), "domain_smoke.png")


# ------------------------------------------------------------------ кляксы
def blot(size, rng):
    """Чернильная клякса: рваная форма из порога шума, внутри редкие «дырки», вокруг круглые капли."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    dx, dy = (xx - size / 2) / (size / 2), (yy - size / 2) / (size / 2)
    r = np.sqrt(dx * dx + dy * dy)
    th = np.arctan2(dy, dx)
    n = value_noise(size, size, 3.0, rng, 5)
    n2 = value_noise(size, size, 9.0, rng, 3)
    k = int(rng.integers(3, 7))
    shape = 0.55 + 0.12 * np.sin(th * k + rng.random() * 6) + 0.45 * (n - 0.5) + 0.12 * (n2 - 0.5)
    body = np.clip((shape - r) * 22.0, 0, 1)
    holes = np.clip((n2 - 0.78) * 12.0, 0, 1) * np.clip((0.75 - r) * 4, 0, 1)
    body = np.clip(body - holes, 0, 1)
    drops = np.zeros_like(r)
    prng = random.Random(int(rng.integers(1 << 30)))
    for _ in range(prng.randint(5, 11)):
        a = prng.uniform(0, math.tau)
        rr = prng.uniform(0.66, 0.93)
        cx, cy = math.cos(a) * rr, math.sin(a) * rr
        sx = prng.uniform(0.025, 0.075)
        sy = sx * prng.uniform(0.6, 1.4)
        drops = np.maximum(drops, np.clip((1.0 - np.sqrt(((dx - cx) / sx) ** 2 + ((dy - cy) / sy) ** 2)) * 6, 0, 1))
    return np.maximum(body, drops) * np.clip((1.0 - r) / 0.04, 0, 1)


def blots():
    s = 512
    rng = np.random.default_rng(31)
    atlas = np.zeros((s * 2, s * 2), np.float32)
    for i in range(4):
        b = blot(s, rng)
        y, x = (i // 2) * s, (i % 2) * s
        atlas[y:y + s, x:x + s] = b
    a = Image.fromarray((atlas * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(1.0))
    rgb = Image.new("RGB", a.size, (255, 255, 255))
    rgb.putalpha(a)
    save(rgb, "domain_blots.png")


# ------------------------------------------------------------------ чёрная дыра
def blackhole():
    s = 1024
    rng = np.random.default_rng(41)
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    dx, dy = (xx - s / 2) / (s / 2), (yy - s / 2) / (s / 2)
    r = np.sqrt(dx * dx + dy * dy)
    th = np.arctan2(dy, dx)
    n = value_noise(s, s, 8.0, rng, 4)
    hole = 0.36
    side = 0.5 + 0.5 * np.cos(th + 2.1)  # толще и ярче сверху-слева
    ring = np.exp(-((r - 0.42 - 0.13 * side) / (0.03 + 0.13 * side + 0.02 * n)) ** 2)
    halo = np.exp(-((r - 0.5 - 0.1 * side) / (0.12 + 0.18 * side)) ** 2) * (0.25 + 0.5 * side)
    # радужный край: снаружи синий/фиолетовый, внутри розовый/жёлтый
    t = np.clip((r - hole) / 0.3, 0, 1)
    rainbow = np.stack([
        0.95 - 0.35 * t + 0.05 * np.sin(th * 3),
        0.75 - 0.45 * t,
        0.8 + 0.2 * t,
    ], -1)
    streak = 0.88 + 0.12 * np.sin(th * 9 + n * 4)
    light = np.clip(ring * streak * 1.6 + halo, 0, 1.6)
    col = rainbow * light[..., None]
    col = np.clip(col + (ring ** 3)[..., None] * 0.6, 0, 1)
    alpha = np.clip(light * 1.1, 0, 1)
    disk = np.clip((hole - r) * 160.0, 0, 1)
    col = col * (1 - disk[..., None])
    alpha = np.maximum(alpha, disk)
    alpha *= np.clip((1.0 - r) / 0.15, 0, 1)
    out = np.dstack([np.clip(col * 255, 0, 255), alpha * 255]).astype(np.uint8)
    save(Image.fromarray(out, "RGBA"), "domain_blackhole.png")


def wings():
    """Белые рваные «крылья» туманности по сторонам чёрной дыры + голубые струи."""
    w, h = 2048, 1024
    rng = np.random.default_rng(51)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = (xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2)
    n = value_noise(w, h, 7.0, rng, 6)
    n2 = value_noise(w, h, 18.0, rng, 3)
    # полоса сужается к краям и слегка изгибается
    curve = dy - 0.12 * dx * dx
    band = np.exp(-(curve / (0.16 + 0.22 * np.abs(dx))) ** 2) * np.clip(1.0 - np.abs(dx) ** 2.2, 0, 1)
    white = np.clip((n * 1.25 + 0.35 * n2 - 0.62 + band * 0.55 - 0.45) * 14.0, 0, 1) * np.clip(band * 3, 0, 1)
    fil = filaments(w, h, rng, 90, wrap_x=False, thick=(1.0, 3.0)) * np.clip(band * 2.2, 0, 1)
    col = np.zeros((h, w, 3), np.float32)
    col += np.array([0.62, 0.72, 1.0]) * np.clip(fil + band * 0.35, 0, 1)[..., None]
    col += np.array([0.75, 0.5, 0.98]) * (np.clip(n2 - 0.62, 0, 1) * band * 1.6)[..., None]
    col = col * (1 - white[..., None]) + white[..., None]
    alpha = np.clip(white + fil * 0.85 + band * 0.22, 0, 1)
    out = np.dstack([np.clip(col * 255, 0, 255), alpha * 255]).astype(np.uint8)
    save(Image.fromarray(out, "RGBA"), "domain_wings.png")


def floor():
    s = 1024
    rng = np.random.default_rng(61)
    n = tileable_noise(s, 2, rng, 5)
    n2 = tileable_noise(s, 6, rng, 3)
    deep = np.array([6, 18, 52], np.float32)
    blue = np.array([24, 70, 140], np.float32)
    col = deep + (blue - deep) * np.clip(n * 1.2 - 0.25, 0, 1)[..., None]
    col += np.array([60, 90, 140], np.float32) * np.clip(n2 - 0.72, 0, 1)[..., None] * 1.5
    save(Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)), "domain_floor.png")


def galaxy():
    w, h = 1920, 1080
    rng = np.random.default_rng(71)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = (xx - w / 2) / (h / 2), (yy - h / 2) / (h / 2)
    r = np.sqrt(dx * dx + dy * dy)
    n = value_noise(w, h, 5.0, rng, 6)
    n2 = value_noise(w, h, 12.0, rng, 4)
    pink = np.array([255, 70, 200], np.float32)
    mag = np.array([200, 30, 240], np.float32)
    blue = np.array([40, 30, 140], np.float32)
    dark = np.array([10, 4, 40], np.float32)
    k = np.clip(n * 1.4 - 0.2, 0, 1)
    col = dark + (blue - dark) * k[..., None]
    col += (mag - col) * np.clip(n2 * 1.5 - 0.55, 0, 1)[..., None]
    col += (pink - col) * np.clip((n - 0.55) * 2.5, 0, 1)[..., None] * 0.7
    col += np.array([255, 230, 255], np.float32) * np.exp(-(r / 0.35) ** 2)[..., None] * 0.6
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    prng = random.Random(72)
    for _ in range(900):
        x, y = prng.uniform(0, w), prng.uniform(0, h)
        rr = prng.choice((0.7, 1.0, 1.4, 2.0))
        c = prng.choice([(255, 255, 255), (255, 180, 240), (190, 200, 255)])
        d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=c)
    img = ImageChops.add(img, img.filter(ImageFilter.GaussianBlur(8)).point(lambda v: v // 2))
    save(img, "domain_galaxy.png")


def cracks():
    w, h = 1920, 1080
    rng = random.Random(81)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy = w / 2, h / 2

    def crack(x, y, a, length, width, depth):
        pts = [(x, y)]
        travelled = 0
        while travelled < length:
            a += rng.uniform(-0.18, 0.18)
            step = rng.uniform(18, 46)
            x += math.cos(a) * step
            y += math.sin(a) * step
            travelled += step
            pts.append((x, y))
            if depth < 2 and rng.random() < 0.12:
                crack(x, y, a + rng.choice((-1, 1)) * rng.uniform(0.4, 1.0), length * 0.35, max(1, width - 1), depth + 1)
        d.line(pts, fill=(255, 255, 255, 235), width=width)

    for i in range(26):
        a = i / 26 * math.tau + rng.uniform(-0.1, 0.1)
        crack(cx, cy, a, rng.uniform(600, 1200), rng.choice((2, 3, 4)), 0)
    # кольцевые трещины
    for k in range(5):
        rad = 90 + k * 120 + rng.uniform(-20, 20)
        pts = []
        for j in range(73):
            t = j / 72 * math.tau
            rr = rad + rng.uniform(-12, 12)
            pts.append((cx + math.cos(t) * rr * 1.25, cy + math.sin(t) * rr))
        for j in range(0, 72, 2):
            if rng.random() < 0.7:
                d.line(pts[j:j + 2], fill=(255, 255, 255, 200), width=2)
    glow = img.filter(ImageFilter.GaussianBlur(4))
    out = Image.alpha_composite(glow, img)
    save(out, "domain_cracks.png")


if __name__ == "__main__":
    void_sky()
    smoke()
    blots()
    blackhole()
    wings()
    floor()
    galaxy()
    cracks()
