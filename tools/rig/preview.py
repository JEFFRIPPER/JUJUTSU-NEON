"""Превью модели R15 и анимаций без игры: кадры, GIF и лист кадров.

  python3 tools/rig/preview.py pose                      — модель в покое с четырёх сторон
  python3 tools/rig/preview.py anim <файл.animation.json> <имя> [--views front,side] [--fps 20]
  python3 tools/rig/preview.py mix  <файл> <имя:вес> ...  — смесь нескольких анимаций (как слой Roblox)

Результат — tools/rig/out/. Скин по умолчанию — tools/rig/skins/preview.png (--skin для другого).
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geckosim as gs  # noqa: E402
import rig  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
GEO = rig.GEO_DIR


def load_tex(path):
    im = Image.open(path).convert('RGBA')
    return np.asarray(im).astype(np.float32) / 255.0


def look_at(yaw_deg, pitch_deg):
    """Матрица вида: поворот мира вокруг Y (камера смотрит на модель), затем наклон."""
    y = math.radians(yaw_deg)
    p = math.radians(pitch_deg)
    ry = gs.m_rot('y', y)
    rx = gs.m_rot('x', p)
    return rx @ ry


class Raster:
    def __init__(self, w, h, scale, center):
        self.w, self.h = w, h
        self.scale = scale
        self.center = center
        self.color = np.zeros((h, w, 3), dtype=np.float32)
        self.color[:] = (0.13, 0.14, 0.17)
        self.depth = np.full((h, w), -1e9, dtype=np.float32)

    def project(self, p):
        # ортографическая проекция: x вправо, y вверх; камера смотрит вдоль -Z (видим грани, смотрящие на +Z)
        sx = self.w / 2 + (p[:, 0] - self.center[0]) * self.scale
        sy = self.h / 2 - (p[:, 1] - self.center[1]) * self.scale
        return np.stack([sx, sy, p[:, 2]], axis=1)

    def floor(self, view, y=0.0):
        # сетка пола для ощущения опоры
        for i in range(-6, 7):
            for a, b in (((i / 4, y, -1.5), (i / 4, y, 1.5)), ((-1.5, y, i / 4), (1.5, y, i / 4))):
                pa = np.array([a, b], dtype=float)
                ph = np.c_[pa, np.ones(2)] @ view.T
                s = self.project(ph[:, :3])
                self.line(s[0], s[1], (0.22, 0.24, 0.28))

    def line(self, a, b, col):
        n = int(max(abs(b[0] - a[0]), abs(b[1] - a[1]))) + 1
        for t in np.linspace(0, 1, n):
            x = int(a[0] + (b[0] - a[0]) * t)
            yy = int(a[1] + (b[1] - a[1]) * t)
            if 0 <= x < self.w and 0 <= yy < self.h:
                self.color[yy, x] = col

    def tri(self, s, uv, tex, shade):
        xs, ys = s[:, 0], s[:, 1]
        x0, x1 = int(max(0, math.floor(xs.min()))), int(min(self.w - 1, math.ceil(xs.max())))
        y0, y1 = int(max(0, math.floor(ys.min()))), int(min(self.h - 1, math.ceil(ys.max())))
        if x1 < x0 or y1 < y0:
            return
        (ax, ay, az), (bx, by, bz), (cx, cy, cz) = s
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9:
            return
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        w0 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
        w1 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
        if not inside.any():
            return
        z = w0 * az + w1 * bz + w2 * cz
        u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
        v = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
        th, tw = tex.shape[:2]
        tu = np.clip((u * tw).astype(int), 0, tw - 1)
        tv = np.clip((v * th).astype(int), 0, th - 1)
        texel = tex[tv, tu]
        region_d = self.depth[y0:y1 + 1, x0:x1 + 1]
        ok = inside & (texel[..., 3] > 0.1) & (z > region_d)
        if not ok.any():
            return
        region_c = self.color[y0:y1 + 1, x0:x1 + 1]
        region_c[ok] = texel[..., :3][ok] * shade
        region_d[ok] = z[ok]

    def draw(self, quads, tex, view):
        light = np.array([0.35, 0.8, 0.5])
        light /= np.linalg.norm(light)
        for verts, uvs, n in quads:
            vh = np.c_[verts, np.ones(4)] @ view.T
            nv = view[:3, :3] @ n
            nl = np.linalg.norm(nv)
            shade = 0.55 + 0.45 * max(0.0, float(np.dot(nv / (nl or 1), light)))
            s = self.project(vh[:, :3])
            self.tri(s[[0, 1, 2]], uvs[[0, 1, 2]], tex, shade)
            self.tri(s[[0, 2, 3]], uvs[[0, 2, 3]], tex, shade)

    def image(self):
        return Image.fromarray((np.clip(self.color, 0, 1) * 255).astype(np.uint8))


VIEWS = {
    # yaw: смотрим на модель спереди (лицо на -Z → разворачиваем на 180°)
    'front': (180, 10),
    'side': (-90, 6),      # правый бок
    'back': (0, 10),
    'three': (215, 16),
    'left': (90, 6),
}


def render(bones, roots, poses, tex, view_name, size=200, root_offset=(0, 0, 0), extra_yaw=0.0):
    yaw, pitch = VIEWS[view_name]
    view = look_at(yaw + extra_yaw, pitch)
    r = Raster(size, int(size * 1.25), size / 3.0, (0, 1.05))
    r.floor(view)
    base = gs.m_translate(*root_offset)
    quads = gs.collect_quads(roots, poses, base)
    r.draw(quads, tex, view)
    return r.image()


def label(img, text):
    d = ImageDraw.Draw(img)
    d.text((4, 2), text, fill=(235, 235, 235))
    return img


def apply_sample(poses, bones, samples, weight_by=None):
    """Наложить выборку анимации на позу (одна анимация, вес 1)."""
    for bname, chans in samples.items():
        if bname not in poses:
            continue
        p = poses[bname]
        b = bones[bname]
        if 'rotation' in chans:
            p.rot = b.init_rot + np.array(chans['rotation'])
        if 'position' in chans:
            p.pos = np.array(chans['position'])
        if 'scale' in chans:
            p.scale = np.array(chans['scale'])


def sheet(frames, cols):
    w, h = frames[0].size
    rows = (len(frames) + cols - 1) // cols
    im = Image.new('RGB', (w * cols, h * rows), (20, 20, 24))
    for i, f in enumerate(frames):
        im.paste(f, ((i % cols) * w, (i // cols) * h))
    return im


def save_gif(frames, path, fps):
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=int(1000 / fps), loop=0, disposal=2)


def model_paths(slim):
    return os.path.join(GEO, 'player_r15_slim.geo.json' if slim else 'player_r15.geo.json')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('mode', choices=['pose', 'anim', 'mix'])
    ap.add_argument('args', nargs='*')
    ap.add_argument('--skin', default=os.path.join(HERE, 'skins', 'preview.png'))
    ap.add_argument('--slim', action='store_true')
    ap.add_argument('--views', default='front,side,three')
    ap.add_argument('--fps', type=float, default=10)
    ap.add_argument('--size', type=int, default=200)
    ap.add_argument('--model')
    ap.add_argument('--out')
    ap.add_argument('--loops', type=float, default=1)
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    tex = load_tex(a.skin)
    bones, roots, _ = gs.load_geo(a.model or model_paths(a.slim))
    views = a.views.split(',')

    if a.mode == 'pose':
        poses = gs.rest_poses(bones)
        imgs = [label(render(bones, roots, poses, tex, v, a.size), v) for v in ['front', 'side', 'back', 'three']]
        out = a.out or os.path.join(OUT, 'pose.png')
        sheet(imgs, 4).save(out)
        print(out)
        return

    path = a.args[0]
    anims = gs.load_anims(path)
    if a.mode == 'anim':
        tracks = [(anims[a.args[1]], 1.0)]
        name = a.args[1]
    else:
        tracks = []
        for spec in a.args[1:]:
            n, w = (spec.split(':') + ['1'])[:2]
            tracks.append((anims[n], float(w)))
        name = '+'.join(t[0].name for t in tracks)
    length = max(t[0].length for t in tracks)
    if length > 1e6:
        length = 20
    n_frames = max(1, int(round(length / 20.0 * a.fps * a.loops)))
    frames = []
    for i in range(n_frames + (0 if tracks[0][0].loop else 1)):
        tick = i * 20.0 / a.fps
        poses = gs.rest_poses(bones)
        mix_into(poses, bones, tracks, tick)
        row = [render(bones, roots, poses, tex, v, a.size) for v in views]
        frame = sheet(row, len(row))
        label(frame, '%s  t=%.2fs' % (name, tick / 20))
        frames.append(frame)
    out = a.out or os.path.join(OUT, name.replace('/', '_'))
    save_gif(frames, out + '.gif', a.fps)
    sheet(frames[::max(1, len(frames) // 12)], 4).save(out + '_sheet.png')
    print(out + '.gif')


def mix_into(poses, bones, tracks, tick):
    """Смесь как в слое Roblox: одинаковый приоритет — среднее по весам (повороты — через кватернионы)."""
    acc = {}
    for anim, w in tracks:
        t = tick
        if anim.loop is True and anim.length > 0:
            t = tick % anim.length
        s = gs.sample(anim, t)
        for bname, chans in s.items():
            for ch, val in chans.items():
                acc.setdefault((bname, ch), []).append((np.array(val), w))
    for (bname, ch), vals in acc.items():
        if bname not in poses:
            continue
        tw = sum(w for _, w in vals)
        if tw <= 0:
            continue
        if ch == 'rotation' and len(vals) > 1:
            q = quat_blend([(euler_to_quat(v), w / tw) for v, w in vals])
            val = quat_to_euler(q)
        else:
            val = sum(v * w for v, w in vals) / tw
        k = min(1.0, tw)
        p = poses[bname]
        b = bones[bname]
        if ch == 'rotation':
            p.rot = b.init_rot + val * k
        elif ch == 'position':
            p.pos = val * k
        else:
            p.scale = 1 + (val - 1) * k


def euler_to_quat(e):
    # R = Rz * Ry * Rx
    x, y, z = e
    cx, sx = math.cos(x / 2), math.sin(x / 2)
    cy, sy = math.cos(y / 2), math.sin(y / 2)
    cz, sz = math.cos(z / 2), math.sin(z / 2)
    return np.array([
        cz * cy * cx + sz * sy * sx,
        cz * cy * sx - sz * sy * cx,
        cz * sy * cx + sz * cy * sx,
        sz * cy * cx - cz * sy * sx,
    ])


def quat_blend(qs):
    ref = qs[0][0]
    acc = np.zeros(4)
    for q, w in qs:
        if np.dot(q, ref) < 0:
            q = -q
        acc += q * w
    return acc / np.linalg.norm(acc)


def quat_to_euler(q):
    w, x, y, z = q
    # обратное к Rz*Ry*Rx
    sy = 2 * (w * y - z * x)
    sy = max(-1.0, min(1.0, sy))
    ey = math.asin(sy)
    ex = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    ez = math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
    return np.array([ex, ey, ez])


if __name__ == '__main__':
    main()
