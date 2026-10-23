"""Повтор математики GeckoLib 4.8.4 на Python (для превью без игры).

Модель: как BakedModelFactory.Builtin (X отражается, повороты костей X/Y со знаком минус, развёртка граней
как в GeoQuad.build). Поза кости: как RenderUtils.prepMatrixForBone. Ключи анимаций: как
BakedAnimationsAdapter + выборка нашего слоя (com.kira.jujutsuneon.rbx.KeyframeSampler) — она совпадает
с GeckoLib, кроме catmullrom (у нас честный сплайн по соседним ключам, как в Blockbench) и step.
"""
import json
import math

import numpy as np


# ------------------------------------------------------------------------------------------ модель

class Bone:
    def __init__(self, name, parent, pivot, rotation):
        self.name = name
        self.parent = parent
        # как GeoBone.updatePivot(-x, y, z) и updateRotation(rad(-x), rad(-y), rad(z))
        self.pivot = np.array([-pivot[0], pivot[1], pivot[2]], dtype=float)
        rx, ry, rz = rotation
        self.init_rot = np.array([math.radians(-rx), math.radians(-ry), math.radians(rz)])
        self.children = []
        self.quads = []  # (verts[4][3] в блоках, uvs[4][2] в 0..1, normal[3])


def load_geo(path):
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    g = data['minecraft:geometry'][0]
    desc = g['description']
    tw, th = desc['texture_width'], desc['texture_height']
    bones = {}
    order = []
    for b in g['bones']:
        bone = Bone(b['name'], b.get('parent'), b.get('pivot', [0, 0, 0]), b.get('rotation', [0, 0, 0]))
        bones[bone.name] = bone
        order.append(bone)
        for c in b.get('cubes', []):
            bone.quads.extend(bake_cube(c, tw, th, b.get('inflate')))
    for bone in order:
        if bone.parent:
            bones[bone.parent].children.append(bone)
    roots = [b for b in order if not b.parent]
    return bones, roots, (tw, th)


def bake_cube(c, tw, th, bone_inflate):
    size = c['size']
    o = c['origin']
    inflate = c.get('inflate', bone_inflate or 0.0) / 16.0
    origin = np.array([-(o[0] + size[0]) / 16.0, o[1] / 16.0, o[2] / 16.0])
    vs = np.array(size) / 16.0
    x0, y0, z0 = origin - inflate
    x1, y1, z1 = origin + vs + inflate
    blb = (x0, y0, z0); brb = (x0, y0, z1); tlb = (x0, y1, z0); trb = (x0, y1, z1)
    tlf = (x1, y1, z0); trf = (x1, y1, z1); blf = (x1, y0, z0); brf = (x1, y0, z1)
    quads_v = {
        'west': [trb, tlb, blb, brb],
        'east': [tlf, trf, brf, blf],
        'north': [tlb, tlf, blf, blb],
        'south': [trf, trb, brb, brf],
        'up': [trb, trf, tlf, tlb],
        'down': [blb, blf, brf, brb],
    }
    normals = {'west': (-1, 0, 0), 'east': (1, 0, 0), 'north': (0, 0, -1), 'south': (0, 0, 1),
               'up': (0, 1, 0), 'down': (0, -1, 0)}
    uv = c['uv']
    out = []
    if isinstance(uv, list):
        raise NotImplementedError('box UV в превью не нужен')
    mirror = c.get('mirror') is True
    for face in ('west', 'east', 'north', 'south', 'up', 'down'):
        f = uv.get(face)
        if f is None:
            continue
        verts = quads_v[face]
        if mirror:
            verts = quads_v[{'west': 'east', 'east': 'west', 'up': 'down', 'down': 'up'}.get(face, face)]
        u, v = f['uv']
        us, vsz = f['uv_size']
        u_w = (u + us) / tw
        v_h = (v + vsz) / th
        u /= tw
        v /= th
        n = np.array(normals[face], dtype=float)
        if not mirror:
            u, u_w = u_w, u
        else:
            n = n * np.array([-1, 1, 1])
        uvs = [(u, v), (u_w, v), (u_w, v_h), (u, v_h)]
        out.append((np.array(verts, dtype=float), np.array(uvs, dtype=float), n))
    return out


# ------------------------------------------------------------------------------------------ матрицы

def m_translate(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def m_rot(axis, a):
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    if axis == 'x':
        m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    elif axis == 'y':
        m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    else:
        m[0, 0], m[0, 1], m[1, 0], m[1, 1] = c, -s, s, c
    return m


def m_scale(x, y, z):
    return np.diag([x, y, z, 1.0])


class BonePose:
    __slots__ = ('rot', 'pos', 'scale', 'hidden')

    def __init__(self):
        self.rot = np.zeros(3)   # радианы, как у GeoBone (уже с учётом знаков)
        self.pos = np.zeros(3)   # пиксели
        self.scale = np.ones(3)
        self.hidden = False


def bone_matrix(bone, pose):
    """RenderUtils.prepMatrixForBone: позиция, к точке вращения, Z→Y→X, масштаб, обратно."""
    p = pose
    px, py, pz = bone.pivot / 16.0
    m = m_translate(-p.pos[0] / 16.0, p.pos[1] / 16.0, p.pos[2] / 16.0)
    m = m @ m_translate(px, py, pz)
    m = m @ m_rot('z', p.rot[2]) @ m_rot('y', p.rot[1]) @ m_rot('x', p.rot[0])
    m = m @ m_scale(*p.scale)
    m = m @ m_translate(-px, -py, -pz)
    return m


def collect_quads(roots, poses, base=np.eye(4), hide=()):
    """Все грани в пространстве модели с учётом иерархии."""
    out = []

    def walk(bone, parent_m):
        m = parent_m @ bone_matrix(bone, poses[bone.name])
        if bone.name not in hide and not poses[bone.name].hidden:
            for verts, uvs, n in bone.quads:
                vh = np.c_[verts, np.ones(4)] @ m.T
                nn = m[:3, :3] @ n
                out.append((vh[:, :3], uvs, nn))
        for c in bone.children:
            walk(c, m)

    for r in roots:
        walk(r, base)
    return out


def rest_poses(bones):
    poses = {}
    for name, b in bones.items():
        p = BonePose()
        p.rot = b.init_rot.copy()
        poses[name] = p
    return poses


# ------------------------------------------------------------------------------------------ анимации

EASINGS = {}


def _ease_in(f):
    return f


def _ease_out(f):
    return lambda t: 1 - f(1 - t)


def _ease_in_out(f):
    return lambda t: f(t * 2) / 2 if t < 0.5 else 1 - f((1 - t) * 2) / 2


def _reg():
    base = {
        'sine': lambda n: 1 - math.cos(n * math.pi / 2),
        'quad': lambda n: n * n,
        'cubic': lambda n: n ** 3,
        'quart': lambda n: n ** 4,
        'quint': lambda n: n ** 5,
        'expo': lambda n: 2 ** (10 * (n - 1)),
        'circ': lambda n: 1 - math.sqrt(max(0.0, 1 - n * n)),
        'back': lambda n: n * n * ((1.70158 + 1) * n - 1.70158),
    }
    for k, f in base.items():
        EASINGS['easein' + k] = _ease_in(f)
        EASINGS['easeout' + k] = _ease_out(f)
        EASINGS['easeinout' + k] = _ease_in_out(f)
    # как в GeckoLib: easeinquint на самом деле pow(4)
    EASINGS['easeinquint'] = lambda n: n ** 4
    EASINGS['linear'] = lambda n: n
    EASINGS['none'] = EASINGS['linear']


_reg()


class Key:
    __slots__ = ('length', 'start', 'end', 'easing')

    def __init__(self, length, start, end, easing):
        self.length, self.start, self.end, self.easing = length, start, end, easing


def _frames(element):
    """BakedAnimationsAdapter.getKeyframes: [(время, вектор, easing)]."""
    if element is None:
        return []
    if not isinstance(element, (dict, list)):
        element = [element, element, element]
    if isinstance(element, list):
        return [(0.0, element, 'linear')]
    if 'vector' in element:
        return [(0.0, element['vector'], element.get('easing', 'linear'))]
    out = []
    for k, v in element.items():
        t = float(k)
        if isinstance(v, dict) and 'vector' not in v:
            if 'pre' in v:
                pre = v['pre']
                pre = pre['vector'] if isinstance(pre, dict) and 'vector' in pre else pre
                out.append((t if t == 0 else t - 0.001, pre, 'linear'))
            if 'post' in v:
                post = v['post']
                post = post['vector'] if isinstance(post, dict) and 'vector' in post else post
                out.append((t, post, v.get('lerp_mode', 'linear')))
        elif isinstance(v, dict):
            out.append((t, v['vector'], v.get('easing', 'linear')))
        else:
            out.append((t, v, 'linear'))
    return out


def _num(x):
    if isinstance(x, (int, float)):
        return float(x)
    try:
        return float(x)
    except ValueError:
        raise ValueError('molang в превью не поддержан: %r' % (x,))


def build_stack(element, rotation):
    entries = _frames(element)
    if not entries:
        return None
    axes = [[], [], []]
    prev = None
    prev_t = 0.0
    for t, vec, easing in entries:
        vals = [_num(v) for v in vec]
        if rotation:
            vals = [math.radians(-vals[0]), math.radians(-vals[1]), math.radians(vals[2])]
        for i in range(3):
            axes[i].append(Key((t - prev_t) * 20.0, vals[i] if prev is None else prev[i], vals[i], easing))
        prev = vals
        prev_t = t
    return axes


class Anim:
    def __init__(self, name, obj):
        self.name = name
        loop = obj.get('loop', False)
        self.loop = 'hold' if loop == 'hold_on_last_frame' else bool(loop)
        self.bones = {}
        length = 0.0
        for bname, bobj in obj.get('bones', {}).items():
            st = {}
            for ch in ('rotation', 'position', 'scale'):
                s = build_stack(bobj.get(ch), ch == 'rotation')
                if s:
                    st[ch] = s
                    length = max(length, max(sum(k.length for k in ax) for ax in s))
            self.bones[bname] = st
        self.length = obj['animation_length'] * 20.0 if 'animation_length' in obj else length
        self.timeline = {float(k) * 20.0: v for k, v in obj.get('timeline', {}).items()}


def load_anims(path):
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    return {k: Anim(k, v) for k, v in data['animations'].items()}


def sample_axis(keys, tick):
    """Значение оси в момент tick (тики). Совпадает с KeyframeSampler в моде."""
    total = 0.0
    idx = len(keys) - 1
    local = tick
    for i, k in enumerate(keys):
        total += k.length
        if total > tick:
            idx = i
            local = tick - (total - k.length)
            break
    k = keys[idx]
    if local >= k.length:
        return k.end
    t = local / k.length if k.length > 0 else 1.0
    e = k.easing
    if e == 'catmullrom':
        p0 = keys[idx - 1].start if idx > 0 else k.start
        p3 = keys[idx + 1].end if idx + 1 < len(keys) else k.end
        p1, p2 = k.start, k.end
        return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                      + (3 * p1 - p0 - 3 * p2 + p3) * t ** 3)
    if e == 'step':
        return k.start
    f = EASINGS.get(e, EASINGS['linear'])
    return k.start + (k.end - k.start) * f(t)


def sample(anim, tick):
    """{кость: {канал: (x,y,z)}} в момент tick."""
    out = {}
    for bname, st in anim.bones.items():
        d = {}
        for ch, axes in st.items():
            d[ch] = tuple(sample_axis(ax, tick) for ax in axes)
        out[bname] = d
    return out
