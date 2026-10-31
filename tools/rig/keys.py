"""Крупные кадры анимации в заданные моменты (для проверки ключевых поз).

  python3 tools/rig/keys.py <файл> <имя> 0.15,0.65,0.74 [--views three,side,front,back] [--size 260]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geckosim as gs  # noqa: E402
import preview as pv  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument('file')
ap.add_argument('name')
ap.add_argument('times')
ap.add_argument('--views', default='three,side,front,back')
ap.add_argument('--size', type=int, default=260)
ap.add_argument('--out')
a = ap.parse_args()
tex = pv.load_tex(os.path.join(pv.HERE, 'skins', 'preview.png'))
bones, roots, _ = gs.load_geo(pv.model_paths(False))
anim = gs.load_anims(a.file)[a.name]
rows = []
for t in [float(x) for x in a.times.split(',')]:
    poses = gs.rest_poses(bones)
    pv.mix_into(poses, bones, [(anim, 1.0)], t * 20)
    row = pv.sheet([pv.render(bones, roots, poses, tex, v, a.size) for v in a.views.split(',')], len(a.views.split(',')))
    pv.label(row, '%s t=%.2f' % (a.name.split('.')[-1], t))
    rows.append(row)
out = a.out or os.path.join(pv.OUT, a.name.split('.')[-1] + '_keys.png')
pv.sheet(rows, 1).save(out)
print(out)
