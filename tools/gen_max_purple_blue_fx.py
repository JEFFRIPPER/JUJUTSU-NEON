#!/usr/bin/env python3
"""Спрайты Синего во вступлении Максимального Фиолетового (своя графика, стиль аниме-«жидкости»):

  max_purple_bsplash_0..5.png — плоские cel-брызги: тёмно-синяя кромка, голубая заливка, светлая середина,
                                белые блики; рваные «языки» и капли вокруг.
  max_purple_bswirl.png       — водоворот из штрихов-дуг (синие, голубые, белые) вокруг прозрачного центра.
  max_purple_bsplat.png       — лужа-клякса на землю (сверху), тёмно-синяя с голубым краем.
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/jujutsu_neon/textures/gui"
S = 512          # итоговый размер
SS = 2           # суперсэмплинг
N = S * SS

DARK = (18, 52, 205)
MID = (52, 128, 255)
LIGHT = (150, 214, 255)
WHITE = (240, 250, 255)


def tendril(dr, rng, cx, cy, ang, length, width):
    """Сужающийся изогнутый «язык» жидкости."""
    pts_l, pts_r = [], []
    bend = rng.uniform(-0.9, 0.9)
    steps = 18
    for i in range(steps + 1):
        u = i / steps
        a = ang + bend * u * u
        r = length * u
        x, y = cx + math.cos(a) * r, cy + math.sin(a) * r
        w = width * (1.0 - u) ** 1.3 * (1.0 + 0.25 * math.sin(u * 9 + ang))
        nx, ny = -math.sin(a), math.cos(a)
        pts_l.append((x + nx * w, y + ny * w))
        pts_r.append((x - nx * w, y - ny * w))
    dr.polygon(pts_l + pts_r[::-1], fill=255)


def blob_mask(rng, spiky=1.0, drops=10):
    m = Image.new("L", (N, N), 0)
    dr = ImageDraw.Draw(m)
    c = N / 2
    core = N * rng.uniform(0.15, 0.2)
    for _ in range(7):
        a = rng.uniform(0, 2 * math.pi)
        d = core * rng.uniform(0.0, 0.7)
        r = core * rng.uniform(0.55, 1.0)
        x, y = c + math.cos(a) * d, c + math.sin(a) * d
        dr.ellipse([x - r, y - r, x + r, y + r], fill=255)
    for _ in range(int(rng.randint(4, 7) * spiky)):
        a = rng.uniform(0, 2 * math.pi)
        tendril(dr, rng, c + math.cos(a) * core * 0.5, c + math.sin(a) * core * 0.5, a,
                N * rng.uniform(0.18, 0.44), core * rng.uniform(0.25, 0.5))
    for _ in range(drops):
        a = rng.uniform(0, 2 * math.pi)
        d = N * rng.uniform(0.28, 0.46)
        r = N * rng.uniform(0.008, 0.03)
        x, y = c + math.cos(a) * d, c + math.sin(a) * d
        dr.ellipse([x - r, y - r * rng.uniform(0.6, 1.0), x + r, y + r], fill=255)
    # мягко округлить и снова сделать жёсткий край (cel)
    m = m.filter(ImageFilter.GaussianBlur(N * 0.006)).point(lambda v: 255 if v > 120 else 0)
    return m


def erode(m, px):
    k = max(3, int(px) | 1)
    return m.filter(ImageFilter.MinFilter(k))


def shift(m, dx, dy):
    return m.transform(m.size, Image.AFFINE, (1, 0, -dx, 0, 1, -dy))


def layer(img, mask, color):
    img.paste(Image.new("RGBA", img.size, color + (255,)), (0, 0), mask)


def splash(seed):
    rng = random.Random(seed)
    m = blob_mask(rng, spiky=rng.uniform(0.8, 1.3), drops=rng.randint(6, 14))
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    layer(img, m, DARK)
    mid = Image.composite(shift(erode(m, N * 0.02), -N * 0.008, -N * 0.01), Image.new("L", m.size, 0), m)
    layer(img, mid, MID)
    light = Image.composite(shift(erode(m, N * 0.11), -N * 0.03, -N * 0.035), Image.new("L", m.size, 0), mid)
    layer(img, light, LIGHT)
    # белые блики: короткие дуги внутри светлой части
    hl = Image.new("L", (N, N), 0)
    hd = ImageDraw.Draw(hl)
    for _ in range(rng.randint(3, 6)):
        cx, cy = N / 2 + rng.uniform(-N * 0.12, N * 0.06), N / 2 + rng.uniform(-N * 0.12, N * 0.06)
        r = N * rng.uniform(0.04, 0.12)
        a0 = rng.uniform(0, 360)
        hd.arc([cx - r, cy - r, cx + r, cy + r], a0, a0 + rng.uniform(40, 110), fill=255, width=int(N * rng.uniform(0.006, 0.014)))
    hl = Image.composite(hl, Image.new("L", m.size, 0), light)
    layer(img, hl, WHITE)
    return img.resize((S, S), Image.LANCZOS)


def swirl(seed=7):
    rng = random.Random(seed)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    c = N / 2
    for i in range(46):
        r0 = N * rng.uniform(0.1, 0.46)
        a0 = rng.uniform(0, 2 * math.pi)
        sweep = rng.uniform(0.8, 2.4)
        w = N * rng.uniform(0.004, 0.02)
        col = rng.choice([DARK, MID, MID, LIGHT, LIGHT, WHITE])
        pts = []
        for k in range(40):
            u = k / 39
            a = a0 + sweep * u
            r = r0 * (1.0 - 0.35 * u)  # закручивается внутрь
            pts.append((c + math.cos(a) * r, c + math.sin(a) * r))
        # сужение к концам — несколько проходов разной толщины
        for j, f in enumerate((1.0, 0.6, 0.3)):
            seg = pts[int(len(pts) * (0.5 - 0.5 * f)):int(len(pts) * (0.5 + 0.5 * f)) + 1]
            if len(seg) > 1:
                dr.line(seg, fill=col + (255,), width=max(2, int(w * (0.5 + j * 0.5))), joint="curve")
    # центр прозрачный, края гаснут
    y, x = np.mgrid[0:N, 0:N].astype(np.float32)
    d = np.sqrt((x - c) ** 2 + (y - c) ** 2) / (N / 2)
    a = np.asarray(img).astype(np.float32)
    fade = np.clip((d - 0.12) / 0.12, 0, 1) * np.clip((1.0 - d) / 0.12, 0, 1)
    a[..., 3] *= fade
    return Image.fromarray(a.astype(np.uint8)).resize((S, S), Image.LANCZOS)


def splat(seed=99):
    rng = random.Random(seed)
    m = blob_mask(rng, spiky=1.4, drops=18)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    layer(img, m, MID)
    inner = erode(m, N * 0.025)
    layer(img, inner, DARK)
    return img.resize((S, S), Image.LANCZOS)


def main():
    for i in range(6):
        splash(300 + i * 17).save(OUT / f"max_purple_bsplash_{i}.png", optimize=True)
    swirl().save(OUT / "max_purple_bswirl.png", optimize=True)
    splat().save(OUT / "max_purple_bsplat.png", optimize=True)
    print("ok")


if __name__ == "__main__":
    main()
