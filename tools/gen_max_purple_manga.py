#!/usr/bin/env python3
"""Манга-кадры кат-сцены Максимального Фиолетового (свои, процедурные).

Серия А (появление Красного): max_purple_manga_a1.png, max_purple_manga_a2.png
Серия Б (слияние): max_purple_manga_b1.png ... max_purple_manga_b5.png

Рисуется с суперсэмплингом x2 и уменьшается до 1920x1080 — чистые, сглаженные линии.
Центр кадров A1, A2 и B5 оставлен светлым: поверх в игре рисуется чёрный силуэт
самого игрока (его модель в текущей позе).
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/jujutsu_neon/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)

W0, H0 = 1920, 1080
SS = 2
W, H = W0 * SS, H0 * SS
DIAG = math.hypot(W, H)


# ------------------------------------------------------------------ helpers
def grid():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    return xx, yy


XX, YY = grid()


def radial(cx, cy, r, power=1.0):
    d = np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2) / r
    return np.clip(1.0 - d, 0.0, 1.0) ** power


def to_img(arr):
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))


def lerp_rgb(a, b, t):
    a = np.array(a, np.float32)
    b = np.array(b, np.float32)
    return a + (b - a) * t[..., None]


def focus_lines(draw, cx, cy, n, rin, width, color, rng, jitter_center=0.0, rout=None):
    """Классические манга-линии фокуса: клинья от края кадра к центру."""
    rout = rout or DIAG
    for _ in range(n):
        a = rng.uniform(0, math.tau)
        r_in = rng.uniform(*rin)
        w = rng.uniform(*width)
        da = w / rout
        ox = cx + rng.uniform(-jitter_center, jitter_center)
        oy = cy + rng.uniform(-jitter_center, jitter_center)
        p_tip = (ox + math.cos(a) * r_in, oy + math.sin(a) * r_in)
        p1 = (ox + math.cos(a - da) * rout, oy + math.sin(a - da) * rout)
        p2 = (ox + math.cos(a + da) * rout, oy + math.sin(a + da) * rout)
        draw.polygon([p_tip, p1, p2], fill=color)


def astroid(cx, cy, rx, ry, p=7.0, n=900):
    pts = []
    for i in range(n):
        t = i / n * math.tau
        c, s = math.cos(t), math.sin(t)
        pts.append((cx + math.copysign(abs(c) ** p, c) * rx, cy + math.copysign(abs(s) ** p, s) * ry))
    return pts


def lightning(draw, x, y, a, length, width, color, rng, step=26, branch=0.18, depth=0):
    pts = [(x, y)]
    travelled = 0.0
    while travelled < length:
        a += rng.uniform(-0.55, 0.55)
        x += math.cos(a) * step
        y += math.sin(a) * step
        travelled += step
        pts.append((x, y))
        if depth < 2 and rng.random() < branch:
            lightning(draw, x, y, a + rng.choice((-1, 1)) * rng.uniform(0.5, 1.1),
                      length * rng.uniform(0.2, 0.45), max(2, width * 0.5), color, rng, step, branch * 0.6, depth + 1)
    # сужение к концу
    segs = len(pts) - 1
    for i in range(segs):
        w = max(1, int(width * (1.0 - i / max(1, segs)) + 1))
        draw.line([pts[i], pts[i + 1]], fill=color, width=w)


def brush(draw, pts, w0, w1, color):
    """Мазок кистью с сужением."""
    left, right = [], []
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        if i < n - 1:
            dx, dy = pts[i + 1][0] - x, pts[i + 1][1] - y
        else:
            dx, dy = x - pts[i - 1][0], y - pts[i - 1][1]
        L = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / L, dx / L
        t = i / max(1, n - 1)
        w = (w0 + (w1 - w0) * t) * (0.5 + 0.5 * math.sin(math.pi * min(1.0, t * 1.2 + 0.1)))
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    draw.polygon(left + right[::-1], fill=color)


def halftone(intensity, spacing, angle_deg=45.0):
    """Скринтон: точки на повёрнутой сетке, радиус по яркости (0..1). Возвращает маску 0..1."""
    a = math.radians(angle_deg)
    u = XX * math.cos(a) + YY * math.sin(a)
    v = -XX * math.sin(a) + YY * math.cos(a)
    fu = (u / spacing) % 1.0 - 0.5
    fv = (v / spacing) % 1.0 - 0.5
    d = np.sqrt(fu * fu + fv * fv)
    r = np.sqrt(np.clip(intensity, 0, 1)) * 0.5
    return np.clip((r - d) * spacing * 0.9, 0.0, 1.0)


def glow_layer(layer, radius, strength=1.0):
    g = layer.filter(ImageFilter.GaussianBlur(radius))
    if strength != 1.0:
        g = Image.eval(g, lambda v: min(255, int(v * strength)))
    return g


def add(a, b):
    return ImageChops.add(a, b)


def sparkle(draw, x, y, r, color):
    draw.polygon(astroid(x, y, r, r, 4.0, 120), fill=color)


def grain(img, amount, seed):
    return img


def panel_border(draw, color, width):
    draw.rectangle((0, 0, W - 1, H - 1), outline=color, width=width)


def save(img, name):
    out = img.resize((W0, H0), Image.LANCZOS)
    out.save(OUT / name, optimize=True)
    print(name, (OUT / name).stat().st_size)


# ------------------------------------------------------------------ A1 — Красный, светлый кадр
def frame_a1():
    rng = random.Random(101)
    cx, cy = W * 0.5, H * 0.46
    paper = lerp_rgb((252, 249, 244), (232, 226, 220), np.clip(np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2) / DIAG * 1.6, 0, 1))
    # скринтон по краям
    edge = np.clip((np.sqrt(((XX - cx) / W) ** 2 + ((YY - cy) / H) ** 2) - 0.32) * 2.2, 0, 1)
    dots = halftone(edge * 0.55, 18 * SS)
    paper = paper * (1 - dots[..., None] * 0.85)
    img = to_img(paper)

    # красное свечение за силуэтом
    red = np.zeros((H, W, 3), np.float32)
    g = radial(cx, cy, H * 0.75, 1.6)
    red[..., 0] = 230 * g
    red[..., 1] = 20 * g
    red[..., 2] = 40 * g
    img = to_img(np.asarray(img, np.float32) * (1 - g[..., None] * 0.55) + red * 0.55)

    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 950, (H * 0.30, H * 0.62), (3 * SS, 16 * SS), (8, 6, 10), rng, 30 * SS)
    focus_lines(d, cx, cy, 220, (H * 0.42, H * 0.70), (10 * SS, 34 * SS), (8, 6, 10), rng, 10 * SS)

    # красные чернильные мазки-брызги
    for _ in range(26):
        a = rng.uniform(0, math.tau)
        r0 = rng.uniform(H * 0.28, H * 0.48)
        pts = []
        x, y = cx + math.cos(a) * r0, cy + math.sin(a) * r0
        for _ in range(7):
            pts.append((x, y))
            x += math.cos(a) * rng.uniform(30, 70) * SS
            y += math.sin(a) * rng.uniform(30, 70) * SS
            a += rng.uniform(-0.15, 0.15)
        brush(d, pts, rng.uniform(6, 16) * SS, 1 * SS, (200, 8, 30))
    for _ in range(160):
        a = rng.uniform(0, math.tau)
        r0 = rng.uniform(H * 0.3, H * 0.9)
        x, y = cx + math.cos(a) * r0, cy + math.sin(a) * r0
        s = rng.uniform(2, 9) * SS
        d.ellipse((x - s, y - s, x + s, y + s), fill=(205, 10, 35))

    # горячее красное ядро (правая рука): мягкий градиент белое -> красное -> прозрачное
    hx, hy = W * 0.61, H * 0.40
    halo = radial(hx, hy, H * 0.30, 2.2)
    mid = radial(hx, hy, H * 0.10, 1.2)
    hot = radial(hx, hy, H * 0.035, 1.0)
    arr = np.asarray(img, np.float32)
    arr = arr * (1 - halo[..., None] * 0.6)
    arr[..., 0] += 255 * halo + 255 * mid
    arr[..., 1] += 30 * halo + 20 * mid + 255 * hot
    arr[..., 2] += 60 * halo + 50 * mid + 255 * hot
    img = to_img(arr)
    img = grain(img, 5, 11)
    d = ImageDraw.Draw(img)
    panel_border(d, (8, 6, 10), 14 * SS)
    save(img, "max_purple_manga_a1.png")


# ------------------------------------------------------------------ A2 — Красный, негатив
def frame_a2():
    rng = random.Random(202)
    cx, cy = W * 0.5, H * 0.44
    base = np.zeros((H, W, 3), np.float32)
    g = radial(cx, cy, H * 0.95, 1.2)
    base[..., 0] = 40 + 200 * g
    base[..., 1] = 2 + 10 * g
    base[..., 2] = 8 + 30 * g
    img = to_img(base)
    d = ImageDraw.Draw(img)

    # белые лучи фокуса на тёмном
    focus_lines(d, cx, cy, 700, (H * 0.36, H * 0.66), (2 * SS, 11 * SS), (255, 244, 246), rng, 20 * SS)
    # вспышка за силуэтом
    burst = Image.new("RGB", (W, H), 0)
    bd = ImageDraw.Draw(burst)
    bd.polygon(astroid(cx, cy, W * 0.42, H * 0.52, 5.5), fill=(255, 255, 255))
    for _ in range(46):
        a = rng.uniform(0, math.tau)
        L = rng.uniform(H * 0.3, H * 0.62)
        w = rng.uniform(5, 14) * SS
        px, py = -math.sin(a) * w, math.cos(a) * w
        bx, by = cx + math.cos(a) * H * 0.08, cy + math.sin(a) * H * 0.08
        bd.polygon([(bx + px, by + py), (bx - px, by - py), (cx + math.cos(a) * L, cy + math.sin(a) * L)],
                   fill=(255, 255, 255))
    img = add(img, glow_layer(burst, 22 * SS, 1.0))
    img = add(img, burst)

    # красные молнии-трещины
    cracks = Image.new("RGB", (W, H), 0)
    kd = ImageDraw.Draw(cracks)
    for i in range(9):
        a = i / 9 * math.tau + rng.uniform(-0.2, 0.2)
        lightning(kd, cx + math.cos(a) * H * 0.2, cy + math.sin(a) * H * 0.2, a, H * rng.uniform(0.5, 0.8),
                  7 * SS, (255, 40, 70), rng)
    img = add(img, glow_layer(cracks, 14 * SS, 1.6))
    img = add(img, cracks)

    # скринтон в углах
    edge = np.clip((np.sqrt(((XX - cx) / W) ** 2 + ((YY - cy) / H) ** 2) - 0.38) * 2.0, 0, 1)
    dots = halftone(edge * 0.6, 16 * SS, 30)
    arr = np.asarray(img, np.float32) * (1 - dots[..., None] * 0.7)
    img = grain(to_img(arr), 4, 22)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_a2.png")


# ------------------------------------------------------------------ B1 — сине-белая вспышка
def frame_b1():
    rng = random.Random(303)
    cx, cy = W * 0.52, H * 0.5
    t = np.clip(np.sqrt(((XX - cx) / W) ** 2 + ((YY - cy) / H) ** 2) * 1.5, 0, 1)
    base = lerp_rgb((30, 60, 190), (2, 4, 22), t)
    img = to_img(base)
    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 520, (H * 0.18, H * 0.55), (2 * SS, 9 * SS), (170, 200, 255), rng, 12 * SS)

    flash = Image.new("RGB", (W, H), 0)
    fd = ImageDraw.Draw(flash)
    fd.polygon(astroid(cx, cy, W * 0.62, H * 0.19, 6.0), fill=(255, 255, 255))
    fd.polygon(astroid(cx, cy, W * 0.06, H * 0.42, 6.0), fill=(255, 255, 255))
    img = add(img, glow_layer(flash, 30 * SS, 1.4))
    img = add(img, flash)

    cracks = Image.new("RGB", (W, H), 0)
    kd = ImageDraw.Draw(cracks)
    for a in (-2.6, -0.5, 0.45, 2.7, -1.9, 1.95):
        lightning(kd, cx + math.cos(a) * W * 0.12, cy + math.sin(a) * H * 0.12, a, H * rng.uniform(0.55, 0.85),
                  6 * SS, (230, 240, 255), rng)
    img = add(img, glow_layer(cracks, 10 * SS, 1.4))
    img = add(img, cracks)
    d = ImageDraw.Draw(img)
    for _ in range(40):
        sparkle(d, rng.uniform(0, W), rng.uniform(0, H), rng.uniform(6, 22) * SS, (230, 240, 255))
    dots = halftone(np.clip(t - 0.35, 0, 1) * 1.2, 14 * SS, 15)
    arr = np.asarray(img, np.float32) * (1 - dots[..., None] * 0.6)
    img = grain(to_img(arr), 4, 33)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_b1.png")


def purple_base(cx, cy, scale=1.3):
    t = np.clip(np.sqrt(((XX - cx) / W) ** 2 + ((YY - cy) / H) ** 2) * scale, 0, 1)
    return t, lerp_rgb((170, 40, 230), (24, 2, 48), t)


def gutters(d, color, rng):
    """Белые «межкадровые» полосы-разрывы, как разрезанная страница."""
    x = W * rng.uniform(0.28, 0.34)
    d.polygon([(x - 10 * SS, 0), (x + 14 * SS, 0), (x + 4 * SS, H), (x - 18 * SS, H)], fill=color)
    y = H * rng.uniform(0.68, 0.74)
    d.polygon([(0, y - 9 * SS), (W, y - 16 * SS), (W, y + 6 * SS), (0, y + 12 * SS)], fill=color)


# ------------------------------------------------------------------ B2 — фиолетовая сфера с кольцами
def frame_b2():
    rng = random.Random(404)
    cx, cy = W * 0.56, H * 0.47
    t, base = purple_base(cx, cy)
    img = to_img(base)
    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 420, (H * 0.40, H * 0.7), (2 * SS, 10 * SS), (60, 6, 100), rng, 10 * SS)

    sphere = np.zeros((H, W, 3), np.float32)
    g = radial(cx, cy, H * 0.34, 0.8)
    sphere[..., 0] = 255 * g
    sphere[..., 1] = 150 * g ** 1.5 + 60 * g ** 6
    sphere[..., 2] = 255 * g
    img = add(img, to_img(sphere))

    rings = Image.new("RGB", (W, H), 0)
    rd = ImageDraw.Draw(rings)
    for i, r in enumerate((0.36, 0.45, 0.56, 0.72)):
        rr = H * r
        start = rng.uniform(0, 360)
        for k in range(3):
            a0 = start + k * 120 + rng.uniform(-10, 10)
            rd.arc((cx - rr, cy - rr, cx + rr, cy + rr), a0, a0 + rng.uniform(70, 105),
                   fill=(255, 220, 255), width=int((7 - i) * SS))
    img = add(img, glow_layer(rings, 12 * SS, 1.6))
    img = add(img, rings)

    bolts = Image.new("RGB", (W, H), 0)
    bd = ImageDraw.Draw(bolts)
    for i in range(7):
        a = i / 7 * math.tau + rng.uniform(-0.3, 0.3)
        lightning(bd, cx + math.cos(a) * H * 0.3, cy + math.sin(a) * H * 0.3, a, H * rng.uniform(0.4, 0.7),
                  6 * SS, (255, 235, 255), rng)
    img = add(img, glow_layer(bolts, 12 * SS, 1.5))
    img = add(img, bolts)
    d = ImageDraw.Draw(img)
    gutters(d, (255, 255, 255), rng)
    for _ in range(18):
        sparkle(d, rng.uniform(0, W), rng.uniform(0, H), rng.uniform(10, 40) * SS, (255, 240, 255))
    dots = halftone(np.clip(t - 0.4, 0, 1) * 1.4, 14 * SS, 60)
    arr = np.asarray(img, np.float32) * (1 - dots[..., None] * 0.55)
    img = grain(to_img(arr), 4, 44)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_b2.png")


# ------------------------------------------------------------------ B3 — крест-вспышка
def frame_b3():
    rng = random.Random(505)
    cx, cy = W * 0.55, H * 0.55
    t, base = purple_base(cx, cy, 1.1)
    img = to_img(base)
    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 900, (H * 0.10, H * 0.45), (3 * SS, 14 * SS), (40, 0, 70), rng, 20 * SS)
    focus_lines(d, cx, cy, 300, (H * 0.25, H * 0.6), (2 * SS, 6 * SS), (255, 220, 255), rng, 20 * SS)

    flash = Image.new("RGB", (W, H), 0)
    fd = ImageDraw.Draw(flash)
    fd.polygon(astroid(cx, cy, W * 0.62, H * 0.07, 6.0), fill=(255, 255, 255))
    fd.polygon(astroid(cx, cy, W * 0.035, H * 0.7, 6.0), fill=(255, 255, 255))
    # неровная белая вспышка-клякса в центре
    pts = []
    for i in range(160):
        a = i / 160 * math.tau
        spike = 0.10 * rng.random() ** 3 if i % 2 == 0 else 0.0
        r = H * (0.11 + 0.03 * rng.random() + spike)
        pts.append((cx + math.cos(a) * r * 1.2, cy + math.sin(a) * r))
    fd.polygon(pts, fill=(255, 255, 255))
    img = add(img, glow_layer(flash, 26 * SS, 1.5))
    img = add(img, flash)
    d = ImageDraw.Draw(img)
    for _ in range(12):
        sparkle(d, rng.uniform(0, W), rng.uniform(0, H), rng.uniform(12, 46) * SS, (255, 255, 255))
    dots = halftone(np.clip(t - 0.3, 0, 1) * 1.3, 12 * SS, 45)
    arr = np.asarray(img, np.float32) * (1 - dots[..., None] * 0.55)
    img = grain(to_img(arr), 4, 55)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_b3.png")


# ------------------------------------------------------------------ B4 — наезд в ядро
def frame_b4():
    rng = random.Random(606)
    cx, cy = W * 0.5, H * 0.48
    t, base = purple_base(cx, cy, 0.9)
    img = to_img(base * 0.7)
    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 1400, (H * 0.05, H * 0.3), (2 * SS, 9 * SS), (255, 230, 255), rng, 6 * SS)
    core = Image.new("RGB", (W, H), 0)
    cd = ImageDraw.Draw(core)
    pts = []
    for i in range(120):
        a = i / 120 * math.tau
        r = H * (0.16 + 0.08 * rng.random())
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    cd.polygon(pts, fill=(255, 255, 255))
    img = add(img, glow_layer(core, 40 * SS, 1.8))
    img = add(img, core)
    bolts = Image.new("RGB", (W, H), 0)
    bd = ImageDraw.Draw(bolts)
    for i in range(10):
        a = i / 10 * math.tau + rng.uniform(-0.2, 0.2)
        lightning(bd, cx + math.cos(a) * H * 0.2, cy + math.sin(a) * H * 0.2, a, H * rng.uniform(0.6, 1.0),
                  8 * SS, (255, 210, 255), rng)
    img = add(img, glow_layer(bolts, 14 * SS, 1.4))
    img = add(img, bolts)
    dots = halftone(np.clip(t - 0.25, 0, 1) * 1.2, 12 * SS, 10)
    arr = np.asarray(img, np.float32) * (1 - dots[..., None] * 0.5)
    img = grain(to_img(arr), 4, 66)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_b4.png")


# ------------------------------------------------------------------ B5 — силуэт в молниях
def frame_b5():
    rng = random.Random(707)
    cx, cy = W * 0.5, H * 0.42
    t, base = purple_base(cx, cy, 1.0)
    img = to_img(base)
    aura = np.zeros((H, W, 3), np.float32)
    g = radial(cx, cy, H * 0.9, 1.3)
    aura[..., 0] = 255 * g
    aura[..., 1] = 235 * g ** 1.3
    aura[..., 2] = 255 * g
    img = add(img, to_img(aura))
    d = ImageDraw.Draw(img)
    focus_lines(d, cx, cy, 380, (H * 0.55, H * 0.8), (3 * SS, 12 * SS), (255, 255, 255), rng, 10 * SS)
    bolts = Image.new("RGB", (W, H), 0)
    bd = ImageDraw.Draw(bolts)
    for side in (-1, 1):
        for k in range(4):
            x0 = cx + side * W * rng.uniform(0.12, 0.2)
            y0 = rng.uniform(H * 0.1, H * 0.8)
            a = (0.0 if side > 0 else math.pi) + rng.uniform(-0.6, 0.6)
            lightning(bd, x0, y0, a, W * rng.uniform(0.25, 0.4), 9 * SS, (200, 60, 255), rng)
    img = add(img, glow_layer(bolts, 18 * SS, 2.0))
    core = Image.eval(bolts, lambda v: 255 if v > 40 else 0)
    img = add(img, core.filter(ImageFilter.MinFilter(3)))
    d = ImageDraw.Draw(img)
    for _ in range(20):
        sparkle(d, rng.uniform(0, W), rng.uniform(0, H), rng.uniform(8, 30) * SS, (255, 255, 255))
    img = grain(img, 4, 77)
    d = ImageDraw.Draw(img)
    panel_border(d, (0, 0, 0), 14 * SS)
    save(img, "max_purple_manga_b5.png")


if __name__ == "__main__":
    frame_a1()
    frame_a2()
    frame_b1()
    frame_b2()
    frame_b3()
    frame_b4()
    frame_b5()
