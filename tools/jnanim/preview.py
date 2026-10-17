#!/usr/bin/env python3
"""Превью покадровой анимации: модель игрока (как в Майнкрафте) в позе каждого кадра.

Кинематика повторяет Player Animator: части тела, их точки вращения, порядок поворотов
(сначала pitch, потом yaw, потом roll), сгиб локтей/коленей по середине конечности, сгиб корпуса,
поворот всего тела вокруг таза (0,7 блока). Знаки — как в игре (см. jnanim.py).

    python3 preview.py anim.json                       # лист с кадрами + GIF, вид спереди, сбоку и 3/4
    python3 preview.py anim.json --views front --every 1 --frames 0-23 --out out/
    python3 preview.py anim.json --frame 12            # один кадр крупно (для сверки с референсом)

Результат: <out>/<имя>_sheet.png (все кадры с номерами), <имя>.gif (в реальной скорости),
<имя>_fNNN.png (отдельные кадры при --frame / --each).
"""
import argparse
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jnanim import FrameAnim  # noqa: E402

PX_PER_BLOCK = 16.0 / 0.9375  # модель игрока 0,9375 блока на 16 пикселей
HIP = 0.7 * PX_PER_BLOCK      # точка поворота всего тела

COL = {
    "head": (228, 190, 160), "face": (40, 40, 48), "torso": (46, 50, 68), "rightArm": (70, 110, 200),
    "leftArm": (205, 110, 70), "rightLeg": (40, 60, 120), "leftLeg": (120, 55, 40), "hair": (235, 235, 240),
}


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def axis_rot(axis, a):
    axis = axis / np.linalg.norm(axis)
    x, y, z = axis
    c, s, t = math.cos(a), math.sin(a), 1 - math.cos(a)
    return np.array([[t * x * x + c, t * x * y - s * z, t * x * z + s * y],
                     [t * x * y + s * z, t * y * y + c, t * y * z - s * x],
                     [t * x * z - s * y, t * y * z + s * x, t * z * z + c]])


def box(x0, x1, y0, y1, z0, z1):
    return np.array([[x, y, z] for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)], dtype=float)


FACES = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]


def pose_boxes(p):
    """Список (вершины 8×3 в пикселях, цвет, часть) для позы p (словарь канал → градусы/блоки/пиксели)."""
    g = lambda k, d=0.0: (p.get(k) if p.get(k) is not None else d)  # noqa: E731
    R = math.radians
    out = []

    def limb(part, pivot, upper, lower, joint_y, bend_sign_fix=1.0):
        pit, yaw, rol = R(g(f"{part}.pitch")), R(g(f"{part}.yaw")), R(g(f"{part}.roll"))
        bend, bax = R(g(f"{part}.bend")), R(g(f"{part}.bend_axis"))
        off = np.array([g(f"{part}.x"), -g(f"{part}.y"), g(f"{part}.z")])
        M = rz(rol) @ ry(yaw) @ rx(pit)
        # сгиб: нижняя половина поворачивается вокруг оси (X, повёрнутой на bend_axis вокруг длины конечности)
        ax = ry(bax) @ np.array([1.0, 0.0, 0.0])
        B = axis_rot(ax, bend * bend_sign_fix)
        joint = np.array([0.0, joint_y, 0.0])
        pv = np.array(pivot) + off
        res = []
        for verts, lower_part in ((upper, False), (lower, True)):
            v = verts.copy()
            if lower_part:
                v = (B @ (v - joint).T).T + joint
            v = (M @ v.T).T + pv
            res.append(v)
        return res

    # корпус: нижняя половина 12..18, верхняя 18..24 гнётся вокруг (0,18,0)
    tb, tba = R(g("torso.bend")), R(g("torso.bend_axis"))
    T = axis_rot(ry(tba) @ np.array([1.0, 0, 0]), tb)
    tpiv = np.array([0.0, 18.0, 0.0])
    t_off = np.array([g("torso.x"), -g("torso.y"), g("torso.z")])
    TR = rz(R(g("torso.roll"))) @ ry(R(g("torso.yaw"))) @ rx(R(g("torso.pitch")))

    def upper_t(v):
        v = (T @ (v - tpiv).T).T + tpiv
        return (TR @ (v - np.array([0, 24.0, 0])).T).T + np.array([0, 24.0, 0]) + t_off

    out.append((box(-4, 4, 12, 18, -2, 2) + t_off, COL["torso"], "torso"))
    out.append((upper_t(box(-4, 4, 18, 24, -2, 2)), COL["torso"], "torso"))

    # голова: поворот вокруг шеи (0,24,0)
    H = rz(R(g("head.roll"))) @ ry(R(g("head.yaw"))) @ rx(R(g("head.pitch")))
    hoff = np.array([g("head.x"), -g("head.y"), g("head.z")])
    hb = (H @ box(-4, 4, 0, 8, -4, 4).T).T + np.array([0, 24.0, 0]) + hoff
    out.append((upper_t(hb), COL["head"], "head"))
    # глаза — чтобы видеть, куда смотрит голова
    for ex in (-2.5, 1.5):
        e = (H @ box(ex, ex + 1.2, 3.2, 4.4, 4.0, 4.3).T).T + np.array([0, 24.0, 0]) + hoff
        out.append((upper_t(e), COL["face"], "eye"))
    # повязка
    band = (H @ box(-4.15, 4.15, 4.6, 6.0, -4.15, 4.15).T).T + np.array([0, 24.0, 0]) + hoff
    out.append((upper_t(band), COL["hair"], "band"))

    # руки (классические 4 px): точка вращения на 2 px ниже верха, локоть — 6 px от верха
    for part, px in (("rightArm", 5.0), ("leftArm", -5.0)):
        x0, x1 = (-1, 3) if px > 0 else (-3, 1)
        up, lo = box(x0, x1, -4, 2, -2, 2), box(x0, x1, -10, -4, -2, 2)
        for v in limb(part, (px, 22.0, 0.0), up, lo, -4.0):
            out.append((upper_t(v), COL[part], part))
    # ноги: колено посередине
    for part, px in (("rightLeg", 1.9), ("leftLeg", -1.9)):
        up, lo = box(-2, 2, -6, 0, -2, 2), box(-2, 2, -12, -6, -2, 2)
        for v in limb(part, (px, 12.0, 0.0), up, lo, -6.0):
            out.append((v, COL[part], part))

    # всё тело: смещение (блоки) и поворот вокруг таза
    bp, byw, br = R(g("body.pitch")), R(g("body.yaw")), R(g("body.roll"))
    BR = rz(br) @ ry(-byw) @ rx(-bp)
    boff = np.array([-g("body.x"), g("body.y"), g("body.z")]) * PX_PER_BLOCK
    hip = np.array([0, HIP, 0])
    res = []
    for v, c, part in out:
        v = (BR @ (v - hip).T).T + hip + boff
        res.append((v, c, part))
    return res


VIEWS = {
    # (поворот камеры вокруг вертикали, наклон), камера смотрит на персонажа
    "front": (0.0, 8.0),
    "side": (90.0, 6.0),
    "three_quarter": (55.0, 14.0),
    "back": (180.0, 8.0),
    "top": (20.0, 70.0),
}


def render(pose, view="three_quarter", size=(300, 400), scale=6.0, label=None):
    yaw, pitch = VIEWS[view] if isinstance(view, str) else view
    cam = rx(math.radians(-pitch)) @ ry(math.radians(-yaw))
    W, H = size
    img = Image.new("RGB", size, (24, 26, 32))
    d = ImageDraw.Draw(img)
    cx, cy = W / 2, H * 0.86

    def proj(v):
        q = (cam @ v.T).T
        # персонаж смотрит на +Z, камера стоит перед ним (+Z) и смотрит на -Z
        return np.stack([cx - q[:, 0] * scale, cy - q[:, 1] * scale, q[:, 2]], axis=1)

    # пол
    for gx in range(-24, 25, 6):
        a = proj(np.array([[gx, 0, -24.0], [gx, 0, 24.0]]))
        b = proj(np.array([[-24.0, 0, gx], [24.0, 0, gx]]))
        d.line([tuple(a[0][:2]), tuple(a[1][:2])], fill=(44, 47, 56))
        d.line([tuple(b[0][:2]), tuple(b[1][:2])], fill=(44, 47, 56))
    fwd = proj(np.array([[0, 0, 0.0], [0, 0, 14.0]]))
    d.line([tuple(fwd[0][:2]), tuple(fwd[1][:2])], fill=(90, 200, 120), width=2)

    polys = []
    light = np.array([0.4, 0.8, 0.5])
    light /= np.linalg.norm(light)
    for verts, col, part in pose_boxes(pose):
        P = proj(verts)
        Q = (cam @ verts.T).T
        for f in FACES:
            a, b, c = verts[f[0]], verts[f[1]], verts[f[2]]
            n = np.cross(b - a, c - a)
            ln = np.linalg.norm(n)
            if ln < 1e-9:
                continue
            n /= ln
            centre = verts[list(f)].mean(axis=0)
            box_centre = verts.mean(axis=0)
            if np.dot(n, centre - box_centre) < 0:
                n = -n
            nc = cam @ n
            if nc[2] <= 0:  # грань смотрит от камеры
                continue
            shade = 0.55 + 0.45 * max(0.0, float(np.dot(n, light)))
            depth = Q[list(f)][:, 2].mean()
            polys.append((depth, [tuple(P[i][:2]) for i in f], tuple(int(min(255, ch * shade)) for ch in col)))
    polys.sort(key=lambda t: t[0])
    for _, pts, col in polys:
        d.polygon(pts, fill=col, outline=(15, 15, 20))
    if label:
        d.text((6, 4), label, fill=(220, 220, 230), font=_font())
    return img


_FONT = None


def _font():
    global _FONT
    if _FONT is None:
        try:
            _FONT = ImageFont.truetype("DejaVuSans.ttf", 13)
        except OSError:
            _FONT = ImageFont.load_default()
    return _FONT


def parse_range(s, n):
    if not s:
        return list(range(n))
    out = []
    for part in s.split(","):
        if "-" in part:
            a, b = part.split("-")
            out += list(range(int(a), min(n - 1, int(b)) + 1))
        else:
            out.append(int(part))
    return [f for f in out if 0 <= f < n]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("anim")
    ap.add_argument("--views", default="front,side,three_quarter")
    ap.add_argument("--frames", default="")
    ap.add_argument("--every", type=int, default=1, help="каждый N-й кадр на листе")
    ap.add_argument("--frame", type=float, default=None, help="один кадр крупно (можно дробный)")
    ap.add_argument("--each", action="store_true", help="сохранить каждый кадр отдельным PNG")
    ap.add_argument("--out", default="preview_out")
    ap.add_argument("--cols", type=int, default=8)
    a = ap.parse_args()

    anim = FrameAnim.load(a.anim)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    views = [v.strip() for v in a.views.split(",") if v.strip()]

    def pose_at(f):
        if float(f).is_integer():
            return anim[int(f)]
        return {ch: anim.value(f, ch) for ch in anim.channels()}

    if a.frame is not None:
        tiles = [render(pose_at(a.frame), v, (420, 560), 8.5, f"{anim.name}  кадр {a.frame:g}  {v}") for v in views]
        sheet = Image.new("RGB", (420 * len(tiles), 560))
        for i, t in enumerate(tiles):
            sheet.paste(t, (i * 420, 0))
        p = out / f"{anim.name}_f{a.frame:g}.png"
        sheet.save(p)
        print(p)
        return

    frames = parse_range(a.frames, len(anim))[::max(1, a.every)]
    tw, th = 180, 240
    for v in views:
        tiles = [render(anim[f], v, (tw, th), 3.6, f"{f}") for f in frames]
        cols = min(a.cols, len(tiles))
        rows = (len(tiles) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * tw, rows * th), (10, 10, 12))
        for i, t in enumerate(tiles):
            sheet.paste(t, ((i % cols) * tw, (i // cols) * th))
        p = out / f"{anim.name}_{v}_sheet.png"
        sheet.save(p)
        print(p)
        if a.each:
            for f in frames:
                render(anim[f], v, (420, 560), 8.5, f"{anim.name} {f} {v}").save(out / f"{anim.name}_{v}_f{f:03d}.png")
    # GIF в реальной скорости (вид 3/4 или первый)
    gv = "three_quarter" if "three_quarter" in views else views[0]
    gif = [render(anim[f], gv, (300, 400), 6.0, f"{anim.name} {f}") for f in range(len(anim))]
    p = out / f"{anim.name}.gif"
    gif[0].save(p, save_all=True, append_images=gif[1:], duration=int(1000 / anim.fps), loop=0)
    print(p)


if __name__ == "__main__":
    main()
