#!/usr/bin/env python3
"""Текстуры новой кат-сцены Максимального Фиолетового (свои, процедурные).

Кадры-вставки 1920x1080 (рисуются с суперсэмплингом x2):
  max_purple_manga_a1/a2 — чёрный кадр с белой штриховкой «каракулями» (появление Красного)
  max_purple_hatch       — прозрачный слой чёрной штриховки поверх белого силуэта игрока
  max_purple_insert_1..4 — четыре аниме-вставки перед слиянием
  max_purple_manga_c1    — белый кадр с плотной диагональной штриховкой (удар рукой)
  max_purple_manga_c2    — тёмный градиент с белым «X»
Текстуры для 3D:
  max_purple_space  — тёмный «космос» с лучами и искрами (фон, когда шары сходятся)
  max_purple_swirl  — фиолетово-белая спираль (огромная сфера и белый вихрь взрыва)
  max_purple_splash — капля-брызга (тонируется в игре)
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


def save(img, name, size=(W0, H0)):
    out = img.resize(size, Image.LANCZOS) if img.size != size else img
    out.save(OUT / name, optimize=True)
    print(name, (OUT / name).stat().st_size)


def value_noise(w, h, scale, rng, octaves=5):
    total = np.zeros((h, w), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        cw = max(2, int(scale * (2 ** o)))
        ch = max(2, int(scale * h / w * (2 ** o)))
        grid = rng.random((ch + 1, cw + 1)).astype(np.float32)
        img = Image.fromarray((grid * 255).astype(np.uint8), "L").resize((w, h), Image.BICUBIC)
        total += np.asarray(img, np.float32) / 255.0 * amp
        norm += amp
        amp *= 0.5
    return total / norm


def tapered(draw, pts, w0, w1, color):
    """Мазок с сужением к концам."""
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
        w = (w0 + (w1 - w0) * t) * math.sin(math.pi * min(1.0, 0.06 + t * 0.94)) ** 0.6
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    draw.polygon(left + right[::-1], fill=color)


def scribble(rng, x0, y_from, y_to, loops=True):
    """Вертикальная линия-каракуля с петлями, как пером от руки."""
    pts = []
    steps = int(abs(y_to - y_from) / 7) + 2
    drift_a = rng.uniform(-60, 60)
    f1, f2 = rng.uniform(0.6, 2.2), rng.uniform(3.0, 7.0)
    p1, p2 = rng.uniform(0, math.tau), rng.uniform(0, math.tau)
    loop_r = rng.uniform(8, 34) if loops else 0
    loop_k = rng.uniform(40, 90)
    loop_zone = (rng.uniform(0, 1), rng.uniform(0.08, 0.35))
    for i in range(steps):
        s = i / (steps - 1)
        y = y_from + (y_to - y_from) * s
        x = x0 + drift_a * s + 28 * math.sin(s * f1 * math.tau + p1) + 9 * math.sin(s * f2 * math.tau + p2)
        z = abs(s - loop_zone[0]) < loop_zone[1]
        if z and loop_r:
            ph = s * loop_k
            x += loop_r * math.cos(ph)
            y += loop_r * math.sin(ph) * 1.6
        pts.append((x, y))
    return pts


def polyline(draw, pts, width, color):
    draw.line(pts, fill=color, width=width, joint="curve")


# ------------------------------------------------------------------ A — чёрный кадр, белые каракули
def manga_a(seed, name, focus):
    rng = random.Random(seed)
    img = Image.new("RGB", (W, H), (4, 4, 6))
    d = ImageDraw.Draw(img)
    for _ in range(210):
        x0 = rng.uniform(-80, W + 80)
        y0 = rng.uniform(-200, H * 0.35)
        y1 = rng.uniform(H * 0.55, H + 200)
        pts = scribble(rng, x0, y0, y1, loops=rng.random() < 0.55)
        c = rng.randint(200, 255)
        polyline(d, pts, rng.choice((2, 3, 3, 4, 5, 7)), (c, c, c))
    # редкие длинные росчерки поверх
    for _ in range(26):
        x0 = rng.uniform(0, W)
        pts = scribble(rng, x0, -100, H + 100, loops=True)
        polyline(d, pts, rng.choice((8, 10, 12)), (255, 255, 255))
    if focus:
        cx, cy = W * 0.5, H * 0.52
        for _ in range(140):
            a = rng.uniform(0, math.tau)
            r0 = rng.uniform(0.42, 0.62) * H
            wd = rng.uniform(4, 18)
            da = wd / DIAG
            d.polygon([(cx + math.cos(a) * r0, cy + math.sin(a) * r0),
                       (cx + math.cos(a - da) * DIAG, cy + math.sin(a - da) * DIAG),
                       (cx + math.cos(a + da) * DIAG, cy + math.sin(a + da) * DIAG)], fill=(255, 255, 255))
    # тёмная виньетка в центре — там будет белый силуэт игрока
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    rr = np.sqrt(((xx - W * 0.5) / (W * 0.30)) ** 2 + ((yy - H * 0.58) / (H * 0.55)) ** 2)
    k = np.clip(1.15 - rr, 0, 1)[..., None] * 0.55
    arr = np.asarray(img, np.float32) * (1 - k)
    save(Image.fromarray(arr.astype(np.uint8)), name)


# ------------------------------------------------------------------ штриховка для силуэта
def hatch():
    rng = random.Random(77)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _ in range(2600):
        x = rng.uniform(-50, W + 50)
        y = rng.uniform(-50, H + 50)
        ang = math.radians(rng.uniform(62, 80))
        ln = rng.uniform(40, 180)
        pts = []
        for i in range(8):
            s = i / 7
            pts.append((x + math.cos(ang) * ln * s + math.sin(s * 9 + x) * 3, y + math.sin(ang) * ln * s))
        tapered(d, pts, rng.uniform(1.5, 4.5), rng.uniform(1.0, 3.0), (0, 0, 0, rng.randint(170, 240)))
    save(img, "max_purple_hatch.png")


# ------------------------------------------------------------------ аниме-вставки
def insert_1():
    """Столкновение: красный и синий в центре, фиолетовая молния, тёмно-красный фон с косыми полосами."""
    rng = random.Random(201)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    g = np.clip(yy / H, 0, 1)[..., None]
    base = np.array([96, 4, 14], np.float32) * (1 - g) + np.array([40, 2, 36], np.float32) * g
    img = Image.fromarray(base.astype(np.uint8))
    d = ImageDraw.Draw(img)
    for _ in range(420):
        x = rng.uniform(-W * 0.3, W * 1.3)
        y = rng.uniform(-H * 0.3, H * 1.3)
        ln = rng.uniform(120, 700)
        ang = math.radians(-32 + rng.uniform(-4, 4))
        col = rng.choice([(150, 10, 30), (190, 20, 40), (60, 90, 230), (40, 60, 180), (120, 30, 120), (20, 0, 10)])
        pts = [(x + math.cos(ang) * ln * s, y + math.sin(ang) * ln * s) for s in (0, 0.25, 0.5, 0.75, 1)]
        tapered(d, pts, rng.uniform(2, 14), rng.uniform(1, 6), col)
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = W * 0.5, H * 0.5
    gd.ellipse((cx - 330, cy - 260, cx + 330, cy + 260), fill=(140, 40, 160))
    img = ImageChops.add(img, glow.filter(ImageFilter.GaussianBlur(120)))
    d = ImageDraw.Draw(img)
    # фиолетовая извилистая молния насквозь
    for k in range(3):
        pts = []
        y = -50.0
        x = cx + rng.uniform(-60, 60)
        while y < H + 50:
            pts.append((x, y))
            y += 40
            x += rng.uniform(-50, 50) + (cx - x) * 0.08
        polyline(d, pts, [26, 14, 6][k], [(110, 30, 190), (190, 110, 255), (250, 230, 255)][k])
    # шары
    for (ox, col, hot) in ((-90, (220, 20, 40), (255, 140, 150)), (90, (40, 120, 255), (190, 235, 255))):
        x, y = cx + ox * 2.0, cy + 10
        d.ellipse((x - 190, y - 190, x + 190, y + 190), fill=col)
        d.ellipse((x - 100, y - 100, x + 100, y + 100), fill=hot)
        d.arc((x - 150, y - 150, x + 150, y + 150), 200, 470, fill=(20, 0, 20), width=22)
        d.arc((x - 215, y - 215, x + 215, y + 215), 30, 250, fill=(255, 255, 255), width=10)
    core = Image.new("RGB", (W, H), (0, 0, 0))
    ImageDraw.Draw(core).ellipse((cx - 120, cy - 120, cx + 120, cy + 120), fill=(255, 240, 255))
    img = ImageChops.add(img, core.filter(ImageFilter.GaussianBlur(30)))
    save(img, "max_purple_insert_1.png")


def insert_2():
    """Фиолетовый вихрь с тёмной дырой в центре."""
    rng = np.random.default_rng(202)
    w, h = W0, H0
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = (xx - w * 0.5) / (h * 0.5), (yy - h * 0.5) / (h * 0.5)
    r = np.sqrt(dx * dx + dy * dy) + 1e-4
    th = np.arctan2(dy, dx)
    n = value_noise(w, h, 5.0, rng, 5)
    band = 0.5 + 0.5 * np.sin(th * 4.0 + np.log(r) * 9.0 + n * 5.0)
    band2 = 0.5 + 0.5 * np.sin(th * 9.0 + np.log(r) * 15.0 - n * 3.0)
    deep = np.array([40, 0, 70], np.float32)
    vio = np.array([140, 20, 230], np.float32)
    mag = np.array([240, 70, 255], np.float32)
    lav = np.array([255, 210, 255], np.float32)
    col = deep + (vio - deep) * band[..., None]
    col += (mag - col) * np.clip(band2 - 0.55, 0, 1)[..., None] * 1.6
    rim = np.exp(-((r - 0.24) / 0.07) ** 2)
    col += (lav - col) * rim[..., None] * 0.9
    hole = np.clip((r - 0.17) / 0.06, 0, 1)
    col *= hole[..., None]
    col *= np.clip(1.55 - r * 0.55, 0.35, 1.0)[..., None]
    save(Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)), "max_purple_insert_2.png")


def insert_3():
    """Пурпурный кадр: вертикальные розовые полосы сверху и цепочка тёмных пузырей по диагонали."""
    rng = random.Random(203)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    g = (xx / W)[..., None]
    base = np.array([196, 40, 250], np.float32) * (1 - g) + np.array([170, 20, 240], np.float32) * g
    img = Image.fromarray(base.astype(np.uint8))
    d = ImageDraw.Draw(img)
    for _ in range(170):
        x = rng.uniform(0, W)
        ln = rng.uniform(H * 0.2, H * 1.05)
        y0 = rng.uniform(-200, H * 0.15)
        tilt = rng.uniform(-0.12, 0.05)
        pts = [(x + tilt * ln * s, y0 + ln * s) for s in np.linspace(0, 1, 6)]
        col = rng.choice([(255, 110, 200), (255, 150, 220), (235, 60, 170), (255, 200, 240)])
        tapered(d, pts, rng.uniform(4, 22), rng.uniform(1, 4), col)
    # цепочка пузырей
    for i in range(70):
        s = i / 69
        x = W * (0.08 + 0.84 * s) + rng.uniform(-25, 25)
        y = H * (0.86 - 0.62 * s) + rng.uniform(-30, 30) + 40 * math.sin(s * 9)
        r = rng.uniform(8, 30)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(50, 0, 60))
        d.ellipse((x - r * 0.45, y - r * 0.6, x - r * 0.05, y - r * 0.2), fill=(150, 90, 200))
    # лучи-блики
    for _ in range(10):
        x = rng.uniform(W * 0.2, W * 0.8)
        d.line([(x, 0), (x + rng.uniform(-200, 200), H)], fill=(255, 230, 255), width=rng.randint(3, 8))
    save(img, "max_purple_insert_3.png")


def insert_4():
    """Бледный розовый набросок: шар и вихрь карандашом."""
    rng = random.Random(204)
    img = Image.new("RGB", (W, H), (252, 230, 243))
    d = ImageDraw.Draw(img)
    cx, cy = W * 0.52, H * 0.48
    for k in range(60):
        r = H * rng.uniform(0.18, 0.42)
        a0 = rng.uniform(0, 360)
        sweep = rng.uniform(120, 330)
        squash = rng.uniform(0.75, 1.1)
        box = (cx - r, cy - r * squash, cx + r, cy + r * squash)
        col = rng.choice([(236, 170, 215), (220, 150, 230), (245, 190, 225), (205, 140, 210)])
        d.arc(box, a0, a0 + sweep, fill=col, width=rng.randint(2, 7))
    for _ in range(90):
        a = rng.uniform(0, math.tau)
        r0 = H * rng.uniform(0.45, 0.6)
        r1 = r0 + rng.uniform(100, 600)
        d.line([(cx + math.cos(a) * r0, cy + math.sin(a) * r0), (cx + math.cos(a) * r1, cy + math.sin(a) * r1)],
               fill=(240, 195, 228), width=rng.randint(2, 5))
    for _ in range(25):
        x0 = rng.uniform(0, W)
        pts = scribble(rng, x0, -50, H + 50, loops=True)
        polyline(d, pts, 3, (238, 200, 230))
    img = img.filter(ImageFilter.GaussianBlur(1.5))
    save(img, "max_purple_insert_4.png")


# ------------------------------------------------------------------ C — удар
def manga_c1():
    rng = random.Random(301)
    img = Image.new("RGB", (W, H), (250, 250, 248))
    d = ImageDraw.Draw(img)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    for _ in range(900):
        x = rng.uniform(-W * 0.2, W * 1.2)
        y = rng.uniform(-H * 0.3, H * 1.1)
        # в центре, где силуэт руки, штрихов меньше
        if abs(x - W * 0.55) < W * 0.22 and abs(y - H * 0.42) < H * 0.25 and rng.random() < 0.6:
            continue
        ang = math.radians(118 + rng.uniform(-5, 5))
        ln = rng.uniform(160, 900)
        pts = [(x + math.cos(ang) * ln * s + 6 * math.sin(s * 7 + x), y + math.sin(ang) * ln * s) for s in np.linspace(0, 1, 8)]
        tapered(d, pts, rng.uniform(5, 26), rng.uniform(2, 10), (8, 8, 10))
    save(img, "max_purple_manga_c1.png")


def manga_c2():
    rng = random.Random(302)
    yy = np.mgrid[0:H, 0:W][0].astype(np.float32)
    g = (yy / H)[..., None]
    base = np.array([96, 96, 104], np.float32) * (1 - g) + np.array([6, 6, 8], np.float32) * g
    img = Image.fromarray(base.astype(np.uint8))
    d = ImageDraw.Draw(img)
    cx, cy, s = W * 0.5, H * 0.5, H * 0.34
    for (a, b) in (((-1, -1), (1, 1)), ((1, -1), (-1, 1))):
        for k in range(3):
            j = [rng.uniform(-14, 14) for _ in range(4)]
            pts = [(cx + a[0] * s * (1 - t) + b[0] * s * t + j[0] * math.sin(t * 5),
                    cy + a[1] * s * (1 - t) + b[1] * s * t + j[1] * math.sin(t * 4)) for t in np.linspace(0, 1, 14)]
            tapered(d, pts, [120, 80, 44][k], [60, 36, 16][k], (255, 255, 255))
    save(img, "max_purple_manga_c2.png")


# ------------------------------------------------------------------ 3D: космос, спираль, брызга
def space():
    w, h = 2048, 1152
    rng = np.random.default_rng(401)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx, dy = (xx - w * 0.5) / (h * 0.5), (yy - h * 0.5) / (h * 0.5)
    r = np.sqrt(dx * dx + dy * dy) + 1e-4
    th = np.arctan2(dy, dx)
    n_ang = value_noise(w, h, 3.0, rng, 3)
    # лучи: шум по углу (выборка по углу через синус-сумму)
    rays = np.zeros_like(r)
    for k, amp in ((23, 0.5), (41, 0.35), (67, 0.25), (11, 0.4)):
        ph = rng.uniform(0, math.tau)
        rays += amp * (0.5 + 0.5 * np.sin(th * k + ph + n_ang * 2.0))
    rays = np.clip(rays / 1.5, 0, 1) ** 3.0
    radial = np.clip((r - 0.12) / 0.5, 0, 1) * np.clip(1.6 - r * 0.45, 0, 1)
    base = np.array([4, 6, 24], np.float32)
    navy = np.array([20, 34, 120], np.float32)
    blue = np.array([60, 110, 255], np.float32)
    vio = np.array([110, 40, 210], np.float32)
    col = base + (navy - base) * np.clip(1.0 - r * 0.35, 0, 1)[..., None] * 0.6
    col += (blue - col) * (rays * radial)[..., None] * 0.75
    haze = np.exp(-(((xx - w * 0.72) / (w * 0.32)) ** 2 + ((yy - h * 0.82) / (h * 0.30)) ** 2))
    col += (vio - col) * haze[..., None] * 0.55
    center = np.exp(-(r / 0.35) ** 2)
    col += (np.array([120, 60, 200], np.float32) - col) * center[..., None] * 0.45
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    prng = random.Random(402)
    for _ in range(160):
        x, y = prng.uniform(0, w), prng.uniform(0, h)
        rr = prng.choice((1, 1, 1, 2, 2, 3))
        c = prng.choice([(255, 120, 220), (255, 80, 200), (200, 160, 255), (255, 255, 255)])
        d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=c)
    for _ in range(14):
        x, y = prng.uniform(0, w), prng.uniform(0, h)
        rr = prng.uniform(10, 26)
        pts = []
        for i in range(120):
            t = i / 120 * math.tau
            c_, s_ = math.cos(t), math.sin(t)
            pts.append((x + math.copysign(abs(c_) ** 4, c_) * rr, y + math.copysign(abs(s_) ** 4, s_) * rr))
        d.polygon(pts, fill=(255, 90, 210))
    img = ImageChops.add(img, img.filter(ImageFilter.GaussianBlur(6)).point(lambda v: v // 2))
    save(img, "max_purple_space.png", (w, h))


def swirl():
    S = 1024
    rng = np.random.default_rng(501)
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    dx, dy = (xx - S / 2) / (S / 2), (yy - S / 2) / (S / 2)
    r = np.sqrt(dx * dx + dy * dy) + 1e-4
    th = np.arctan2(dy, dx)
    n = value_noise(S, S, 5.0, rng, 5)
    arm = 0.5 + 0.5 * np.sin(th * 3.0 + np.log(r) * 6.5 + n * 4.0)
    arm2 = 0.5 + 0.5 * np.sin(th * 5.0 + np.log(r) * 10.0 - n * 3.0)
    white = np.array([255, 245, 255], np.float32)
    pink = np.array([250, 150, 255], np.float32)
    mag = np.array([215, 45, 250], np.float32)
    vio = np.array([105, 20, 210], np.float32)
    k = np.clip(r * 1.25 + (arm - 0.5) * 0.45, 0, 1)
    col = np.where(k[..., None] < 0.35, white + (pink - white) * (k / 0.35)[..., None],
                   np.where(k[..., None] < 0.7, pink + (mag - pink) * ((k - 0.35) / 0.35)[..., None],
                            mag + (vio - mag) * ((k - 0.7) / 0.3)[..., None]))
    col += (white - col) * np.clip(arm2 - 0.8, 0, 1)[..., None] * 2.0 * np.clip(1 - r, 0, 1)[..., None]
    alpha = np.clip((1.0 - r) / 0.18, 0, 1) * (0.75 + 0.25 * arm)
    out = np.dstack([np.clip(col, 0, 255), alpha * 255]).astype(np.uint8)
    save(Image.fromarray(out, "RGBA"), "max_purple_swirl.png", (S, S))


def splash():
    S = 256
    rng = np.random.default_rng(601)
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    dx, dy = (xx - S / 2) / (S / 2), (yy - S / 2) / (S / 2)
    r = np.sqrt(dx * dx + dy * dy)
    th = np.arctan2(dy, dx)
    n = value_noise(S, S, 4.0, rng, 4)
    lobes = 0.55 + 0.18 * np.sin(th * 5 + 1.3) + 0.12 * np.sin(th * 9 + 0.4) + 0.25 * (n - 0.5)
    body = np.clip((lobes - r) * 9.0, 0, 1)
    drops = np.zeros_like(r)
    prng = random.Random(602)
    for _ in range(12):
        a = prng.uniform(0, math.tau)
        rr = prng.uniform(0.7, 0.92)
        cx, cy = math.cos(a) * rr, math.sin(a) * rr
        s = prng.uniform(0.03, 0.07)
        drops = np.maximum(drops, np.clip((s - np.sqrt((dx - cx) ** 2 + (dy - cy) ** 2)) * 40, 0, 1))
    a = np.maximum(body, drops)
    light = np.clip(1.1 - r * 0.5, 0.7, 1.0)
    rgb = np.dstack([light * 255] * 3)
    out = np.dstack([rgb, a * 255]).astype(np.uint8)
    save(Image.fromarray(out, "RGBA"), "max_purple_splash.png", (S, S))


if __name__ == "__main__":
    manga_a(101, "max_purple_manga_a1.png", focus=False)
    manga_a(102, "max_purple_manga_a2.png", focus=True)
    hatch()
    insert_1()
    insert_2()
    insert_3()
    insert_4()
    manga_c1()
    manga_c2()
    space()
    swirl()
    splash()
