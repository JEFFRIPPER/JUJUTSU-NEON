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


def hand_poly(cx, cy, s):
    """Кисть «пистолетом» (блочная, своя рисовка): ладонь-кулак + вытянутый указательный палец влево."""
    fist = [(cx - 0.05 * s, cy - 0.32 * s), (cx + 0.62 * s, cy - 0.38 * s), (cx + 0.70 * s, cy + 0.40 * s),
            (cx + 0.02 * s, cy + 0.46 * s)]
    finger = [(cx - 0.95 * s, cy - 0.40 * s), (cx + 0.10 * s, cy - 0.46 * s), (cx + 0.12 * s, cy - 0.12 * s),
              (cx - 0.93 * s, cy - 0.10 * s)]
    thumb = [(cx + 0.18 * s, cy - 0.30 * s), (cx + 0.42 * s, cy - 0.33 * s), (cx + 0.40 * s, cy + 0.05 * s),
             (cx + 0.20 * s, cy + 0.06 * s)]
    return fist, finger, thumb


def sketch_poly(dr, poly, rng, color, strokes=5, width=3):
    """Контур «от руки»: несколько дрожащих обводок."""
    for _ in range(strokes):
        pts = [(x + rng.uniform(-5, 5), y + rng.uniform(-5, 5)) for x, y in poly]
        dr.line(pts + [pts[0]], fill=color, width=width)


def hatch_inside(dr, poly, rng, color, n=40):
    xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
    for _ in range(n):
        x = rng.uniform(min(xs), max(xs)); y = rng.uniform(min(ys), max(ys))
        dr.line([(x, y), (x + rng.uniform(10, 30), y + rng.uniform(-25, -8))], fill=color, width=2)


def manga_hand(rng):
    """Кадр 1: белая диагональная штриховка на чёрном, в центре кисть «пистолетом» белым скетчем."""
    img = Image.new("RGB", (W, H), (0, 0, 0))
    dr = ImageDraw.Draw(img)
    for _ in range(420):
        x = rng.uniform(-400, W + 200); y = rng.uniform(-200, H + 200)
        L = rng.uniform(150, 700); w = rng.uniform(4, 22)
        dr.line([(x, y), (x + L * 0.42, y - L)], fill=(255, 255, 255), width=int(w))
    fist, finger, thumb = hand_poly(W * 0.5, H * 0.5, 420)
    for poly in (finger, fist, thumb):
        dr.polygon(poly, fill=(250, 250, 250))
    for poly in (finger, fist, thumb):
        sketch_poly(dr, poly, rng, (20, 20, 20), 4, 4)
        hatch_inside(dr, poly, rng, (60, 60, 60), 30)
    return img


def manga_x(rng):
    """Кадр 2: слева белый «X» на серо-чёрном градиенте, в центре контур кисти белыми штрихами на чёрном."""
    x = np.linspace(1.0, 0.0, W, dtype=np.float32)
    g = np.clip(x * 1.6 - 0.35, 0, 1) ** 1.4 * 95
    base = np.repeat(np.repeat(g[None, :, None], H, 0), 3, 2)
    img = Image.fromarray(base.astype(np.uint8))
    dr = ImageDraw.Draw(img)
    w = 95
    dr.line([(-120, -60), (520, H + 60)], fill=(255, 255, 255), width=w)
    dr.line([(520, -60), (-120, H + 60)], fill=(255, 255, 255), width=w)
    fist, finger, thumb = hand_poly(W * 0.48, H * 0.47, 330)
    for poly in (finger, fist, thumb):
        sketch_poly(dr, poly, rng, (235, 235, 235), 6, 3)
        hatch_inside(dr, poly, rng, (150, 150, 150), 14)
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
