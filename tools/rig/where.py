"""Где оказываются кисти/голова в позе (пространство модели, блоки; вперёд = −Z модели).

  from where import hand_points; hand_points({'RightUpperArm': (-26, -30, 0), ...})
  → {'RightHand': (вправо, вверх, вперёд), ...}   вправо — в сторону правой руки персонажа
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geckosim as gs  # noqa: E402
import preview as pv  # noqa: E402

_B = None


def _load():
    global _B
    if _B is None:
        _B = gs.load_geo(pv.model_paths(False))
    return _B


def points(pose, names=('RightHand', 'LeftHand', 'Head')):
    bones, roots, _ = _load()
    poses = gs.rest_poses(bones)
    for b, v in pose.items():
        if b.endswith('.pos'):
            poses[b[:-4]].pos = np.array(v, dtype=float)
        else:
            x, y, z = v
            poses[b].rot = bones[b].init_rot + np.radians(np.array([-x, -y, z], dtype=float))
    out = {}

    def walk(bone, m):
        m = m @ gs.bone_matrix(bone, poses[bone.name])
        if bone.name in names:
            c = np.mean([q[0].mean(axis=0) for q in bone.quads], axis=0) if bone.quads else bone.pivot / 16.0
            out[bone.name] = (m @ np.r_[c, 1.0])[:3] * np.array([1, 1, -1])  # вправо, вверх, вперёд
        for ch in bone.children:
            walk(ch, m)

    for r in roots:
        walk(r, np.eye(4))
    return out


if __name__ == '__main__':
    p0 = points({})
    print('rest', {k: np.round(v, 2) for k, v in p0.items()})
    for b, v in [('RightUpperArm', (-90, 0, 0)), ('RightUpperArm', (-26, -30, 0)), ('RightUpperArm', (-26, 30, 0)),
                 ('RightUpperArm', (0, 0, 40))]:
        print(b, v, {k: np.round(x, 2) for k, x in points({b: v}).items() if k == 'RightHand'})
