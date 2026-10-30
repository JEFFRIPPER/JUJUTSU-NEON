#!/usr/bin/env python3
"""Две дополнительные аниме-вставки Максимального Фиолетового (своя графика, 1920×1080):

  max_purple_insert_0.png — столкновение: тёмно-синий фон, диагональные штрихи (синие сверху-справа,
                            красные снизу-слева), Красный и Синий сталкиваются в центре, вспышка.
  max_purple_insert_5.png — ярко-фиолетовый кадр: пузыри, розовые лучи из центра, тёмная «шипастая» точка.
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/jujutsu_neon/textures/gui"


def radial(cx, cy):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    return np.sqrt((x - cx) ** 2 + (y - cy) ** 2)


def glow(img, cx, cy, radius, color, strength=1.0):
    d = radial(cx, cy)
    a = np.clip(1.0 - d / radius, 0, 1) ** 2 * strength
    arr = np.asarray(img).astype(np.float32)
    for c in range(3):
        arr[..., c] = arr[..., c] + (color[c] - arr[..., c] * 0.0) * a
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))


def insert_collision(rng):
    d = radial(W * 0.45, H * 0.55) / W
    base = np.zeros((H, W, 3), np.float32)
    base[..., 0] = 30 - 18 * d
    base[..., 1] = 34 - 20 * d
    base[..., 2] = 120 - 70 * d
    img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8))
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dr = ImageDraw.Draw(layer)
    ang = math.radians(-28)
    ux, uy = math.cos(ang), math.sin(ang)
    for _ in range(260):
        # штрих вдоль диагонали; цвет по стороне от линии удара
        px, py = rng.uniform(-200, W + 200), rng.uniform(-200, H + 200)
        side = (px - W * 0.45) * ux + (py - H * 0.55) * uy  # вдоль диагонали: красные внизу-слева
        L = rng.uniform(60, 420)
        wdt = rng.uniform(2, 9)
        if side > 0:
            col = rng.choice([(90, 170, 255), (140, 220, 255), (40, 110, 255)])
        else:
            col = rng.choice([(255, 60, 50), (255, 120, 70), (220, 30, 60)])
        a = int(rng.uniform(90, 230))
        dr.line([(px, py), (px + ux * L, py + uy * L)], fill=col + (a,), width=int(wdt))
    # «лезвия» Синего справа от центра
    for i in range(7):
        L = 260 + i * 70
        off = (i - 3) * 26
        x0, y0 = W * 0.47, H * 0.53 + off * 0.3
        dr.polygon([(x0, y0 - 6), (x0 + ux * L - uy * off, y0 + uy * L + ux * off), (x0, y0 + 6)],
                   fill=(120, 210, 255, 200))
    layer = layer.filter(ImageFilter.GaussianBlur(1.2))
    img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
    img = glow(img, W * 0.40, H * 0.58, 300, (255, 30, 30), 1.6)
    img = glow(img, W * 0.50, H * 0.52, 200, (90, 200, 255), 1.0)
    img = glow(img, W * 0.455, H * 0.555, 110, (255, 255, 255), 1.2)
    # фиолетовая молния через точку удара
    dr = ImageDraw.Draw(img)
    x, y = W * 0.45, 0.0
    pts = []
    while y < H:
        pts.append((x, y))
        y += rng.uniform(30, 70)
        x = W * 0.45 + rng.uniform(-40, 40)
    dr.line(pts, fill=(210, 120, 255), width=6)
    dr.line(pts, fill=(255, 235, 255), width=2)
    return img


def insert_bubbles(rng):
    d = radial(W * 0.5, H * 0.45) / W
    base = np.zeros((H, W, 3), np.float32)
    base[..., 0] = 200 - 90 * d
    base[..., 1] = 20 + 10 * d
    base[..., 2] = 255 - 40 * d
    img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8)).convert("RGBA")
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dr = ImageDraw.Draw(layer)
    cx, cy = W * 0.5, H * 0.45
    for i in range(22):  # розовые лучи
        a = rng.uniform(0, math.tau)
        L = rng.uniform(500, 1300)
        sp = rng.uniform(0.02, 0.06)
        dr.polygon([(cx, cy), (cx + math.cos(a - sp) * L, cy + math.sin(a - sp) * L),
                    (cx + math.cos(a + sp) * L, cy + math.sin(a + sp) * L)], fill=(255, 70, 200, int(rng.uniform(60, 150))))
    for _ in range(26):  # пузыри
        r = rng.uniform(25, 120)
        x, y = rng.uniform(0, W), rng.uniform(0, H)
        if math.hypot(x - cx, y - cy) < 220:
            continue
        dr.ellipse([x - r, y - r, x + r, y + r], fill=(225, 150, 255, 70), outline=(250, 220, 255, 200), width=max(2, int(r / 14)))
        dr.ellipse([x - r * 0.55, y - r * 0.6, x - r * 0.15, y - r * 0.25], fill=(255, 245, 255, 190))
    layer = layer.filter(ImageFilter.GaussianBlur(1.5))
    img = Image.alpha_composite(img, layer)
    dr = ImageDraw.Draw(img)
    pts = []  # тёмная шипастая точка в центре
    n = 34
    for i in range(n * 2):
        a = math.pi * i / n
        r = (70 + rng.uniform(0, 30)) if i % 2 == 0 else rng.uniform(150, 260)
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r * 0.8))
    dr.polygon(pts, fill=(40, 0, 60, 255))
    dr.ellipse([cx - 60, cy - 50, cx + 60, cy + 50], fill=(15, 0, 25, 255))
    return img.convert("RGB")



def catmull(pts, n=12, closed=True):
    out = []
    P = pts + pts[:3] if closed else [pts[0]] + pts + [pts[-1]]
    m = len(pts) if closed else len(pts) - 1
    for i in range(m):
        p0, p1, p2, p3 = P[i], P[i + 1], P[i + 2], P[i + 3]
        if not closed:
            p0, p1, p2, p3 = P[i], P[i + 1], P[i + 2], P[i + 3]
        for k in range(n):
            t = k / n
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1[j]) + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2
                                    + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3) for j in range(2)))
    if not closed:
        out.append(pts[-1])
    return out

# Кисть «пистолетом» в своих единицах: x — к кончику пальца, y — вверх; запястье в (0,0)
HAND = [(0.00, 0.21), (0.18, 0.25), (0.33, 0.29), (0.40, 0.37), (0.44, 0.50), (0.49, 0.61), (0.56, 0.645),
        (0.615, 0.60), (0.61, 0.48), (0.60, 0.36), (0.66, 0.285), (0.80, 0.27), (0.98, 0.262), (1.13, 0.252),
        (1.205, 0.232), (1.225, 0.19), (1.20, 0.152), (1.12, 0.142), (0.95, 0.140), (0.80, 0.128),
        (0.775, 0.07), (0.775, 0.0), (0.735, -0.06), (0.66, -0.08), (0.655, -0.13), (0.62, -0.185),
        (0.54, -0.20), (0.52, -0.245), (0.47, -0.285), (0.38, -0.285), (0.24, -0.25), (0.10, -0.215),
        (0.00, -0.20)]
# складки и ногти
LINES = [
    [(0.80, 0.128), (0.70, 0.12), (0.62, 0.10)],              # нижний край указательного уходит в ладонь
    [(0.775, 0.0), (0.70, 0.00), (0.62, 0.02)],               # граница среднего пальца
    [(0.66, -0.08), (0.60, -0.07), (0.53, -0.05)],            # средний / безымянный
    [(0.54, -0.20), (0.48, -0.18), (0.42, -0.15)],            # безымянный / мизинец
    [(1.15, 0.236), (1.10, 0.20), (1.15, 0.16)],              # ноготь указательного
    [(0.585, 0.615), (0.555, 0.57), (0.59, 0.53)],            # ноготь большого
    [(0.93, 0.26), (0.95, 0.215)], [(0.93, 0.145), (0.95, 0.18)],  # сгиб пальца
    [(0.60, 0.36), (0.52, 0.33), (0.44, 0.30)],               # основание большого пальца
]
SLEEVE = [(0.04, 0.29), (-0.20, 0.33), (-0.70, 0.36), (-1.60, 0.38), (-1.60, -0.34), (-0.70, -0.31),
          (-0.20, -0.28), (0.04, -0.26), (0.08, 0.0)]
SLEEVE_FOLDS = [[(-0.15, 0.30), (-0.30, 0.10), (-0.22, -0.10)], [(-0.45, 0.36), (-0.55, 0.05)],
                [(-0.62, -0.30), (-0.50, -0.05)], [(-0.80, 0.38), (-0.85, 0.1), (-0.78, -0.12)]]


def tf(pts, cx, cy, s, ang, mirror):
    ca, sa = math.cos(ang), math.sin(ang)
    out = []
    for x, y in pts:
        if mirror: x = -x
        rx, ry = x * ca - y * sa, x * sa + y * ca
        out.append((cx + rx * s, cy - ry * s))
    return out


def jitter(pts, rng, a):
    return [(x + rng.uniform(-a, a), y + rng.uniform(-a, a)) for x, y in pts]


def draw_hand(dr, rng, cx, cy, s, ang=0.0, mirror=True, fill=(250, 250, 250), ink=(15, 15, 15), sleeve=(20, 20, 26),
              lw=7, passes=2, hatch=(70, 70, 70), sleeve_ink=None):
    sl = tf(catmull(SLEEVE, 8), cx, cy, s, ang, mirror)
    dr.polygon(sl, fill=sleeve)
    if sleeve_ink:
        for f in SLEEVE_FOLDS:
            dr.line(tf(catmull(f, 10, False), cx, cy, s, ang, mirror), fill=sleeve_ink, width=max(2, lw // 2))
    outline = tf(catmull(HAND, 10), cx, cy, s, ang, mirror)
    dr.polygon(outline, fill=fill)
    # тень на нижней половине кисти — косая штриховка внутри
    if hatch:
        mask = Image.new("L", dr.im.size, 0)
        ImageDraw.Draw(mask).polygon(outline, fill=255)
        lay = Image.new("L", dr.im.size, 0)
        ld = ImageDraw.Draw(lay)
        base = tf([(0.0, -0.02), (0.75, -0.02)], cx, cy, s, ang, mirror)
        for i in range(70):
            u = rng.uniform(0.02, 0.98)
            x = base[0][0] + (base[1][0] - base[0][0]) * u
            y = base[0][1] + (base[1][1] - base[0][1]) * u + rng.uniform(-0.02, 0.28) * s
            L = rng.uniform(0.05, 0.12) * s
            ld.line([(x, y), (x + L * 0.5, y - L)], fill=255, width=max(2, lw // 3))
        lay = Image.composite(lay, Image.new("L", lay.size, 0), mask)
        dr.bitmap((0, 0), lay, fill=hatch)
    for _ in range(passes):
        dr.line(jitter(outline, rng, lw * 0.25) + [outline[0]], fill=ink, width=lw, joint="curve")
    for ln in LINES:
        dr.line(jitter(tf(catmull(ln, 8, False), cx, cy, s, ang, mirror), rng, lw * 0.15), fill=ink,
                width=max(2, int(lw * 0.6)), joint="curve")




def speed_lines(dr, rng, color, n, wmin, wmax):
    for _ in range(n):
        x = rng.uniform(-400, W + 200); y = rng.uniform(-200, H + 200)
        L = rng.uniform(150, 700); w = rng.uniform(wmin, wmax)
        dr.line([(x, y), (x + L * 0.42, y - L)], fill=color, width=int(w))


def manga_hand(rng):
    """Кадр 1: чёрно-белая диагональная штриховка, по центру кисть «пистолетом» (тушь по белому), рукав чёрный."""
    img = Image.new("RGB", (W, H), (0, 0, 0))
    dr = ImageDraw.Draw(img)
    speed_lines(dr, rng, (255, 255, 255), 420, 4, 22)
    # белая «подложка» за кистью, чтобы контур читался на штриховке
    halo = Image.new("L", (W, H), 0)
    hd = ImageDraw.Draw(halo)
    hd.polygon(tf(catmull(HAND, 10), W * 0.56, H * 0.53, 760, 0.10, True), fill=255)
    halo = halo.filter(ImageFilter.MaxFilter(41))
    dr.bitmap((0, 0), halo, fill=(255, 255, 255))
    draw_hand(dr, rng, W * 0.56, H * 0.53, 760, ang=0.10, sleeve=(8, 8, 10), sleeve_ink=(120, 120, 130), lw=9, passes=3)
    return img


def manga_x(rng):
    """Кадр 2: слева белый «X» на серо-чёрном градиенте, в центре та же кисть белым контуром на чёрном."""
    x = np.linspace(1.0, 0.0, W, dtype=np.float32)
    g = np.clip(x * 1.6 - 0.35, 0, 1) ** 1.4 * 95
    base = np.repeat(np.repeat(g[None, :, None], H, 0), 3, 2)
    img = Image.fromarray(base.astype(np.uint8))
    dr = ImageDraw.Draw(img)
    w = 95
    dr.line([(-120, -60), (520, H + 60)], fill=(255, 255, 255), width=w)
    dr.line([(520, -60), (-120, H + 60)], fill=(255, 255, 255), width=w)
    draw_hand(dr, rng, W * 0.58, H * 0.50, 640, ang=0.10, fill=(6, 6, 8), ink=(240, 240, 240), sleeve=(0, 0, 0),
              sleeve_ink=(200, 200, 210), lw=7, passes=3, hatch=(150, 150, 150))
    return img


def main():
    rng = random.Random(1002)
    insert_collision(rng).save(OUT / "max_purple_insert_0.png", optimize=True)
    insert_bubbles(rng).save(OUT / "max_purple_insert_5.png", optimize=True)
    manga_hand(rng).save(OUT / "max_purple_manga_d1.png", optimize=True)
    manga_x(rng).save(OUT / "max_purple_manga_d2.png", optimize=True)
    print("ok")


if __name__ == "__main__":
    main()
