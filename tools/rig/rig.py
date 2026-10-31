"""Риг игрока R15 для GeckoLib / Blockbench (формат Bedrock geo.json 1.12.0).

Один источник правды для модели игрока, моделей брони и превью:
  python3 tools/rig/rig.py          — записать geo.json в assets/jujutsu_neon/geo/

Кости как у R15 в Roblox: LowerTorso (корень, таз) → UpperTorso → Head, руки Upper/Lower/Hand,
ноги от LowerTorso: UpperLeg/LowerLeg/Foot. Координаты Blockbench: пиксели, Y вверх от земли,
лицо смотрит на -Z (north), правая сторона персонажа — по -X (как у стандартного гуманоида Bedrock).

Внешний слой скина (шапка, куртка, рукава, штаны) — дочерние кости *Layer без своих поворотов:
их прячет отрисовка, если игрок выключил эту часть скина. Анимировать их не нужно.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GEO_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'jujutsu_neon', 'geo')

# Деление конечностей по высоте (пиксели сверху вниз из 12): плечо/предплечье/кисть, бедро/голень/стопа
ARM_SPLIT = (5, 5, 2)
LEG_SPLIT = (6, 4, 2)
TORSO_SPLIT = (6, 6)  # UpperTorso, LowerTorso


class Box:
    """Участок развёртки скина как у кубоида Bedrock (box UV): начало (u, v), размеры w×h×d."""

    def __init__(self, u, v, w, h, d):
        self.u, self.v, self.w, self.h, self.d = u, v, w, h, d

    # Области граней целого кубоида (как в GeckoLib BakedModelFactory для box UV)
    def side(self, face, r0, r1):
        u, v, w, d = self.u, self.v, self.w, self.d
        if face == 'east':
            return [u, v + d + r0], [d, r1 - r0]
        if face == 'west':
            return [u + d + w, v + d + r0], [d, r1 - r0]
        if face == 'north':
            return [u + d, v + d + r0], [w, r1 - r0]
        if face == 'south':
            return [u + 2 * d + w, v + d + r0], [w, r1 - r0]
        raise ValueError(face)

    def top(self):
        return [self.u + self.d, self.v], [self.w, self.d]

    def bottom(self):
        return [self.u + self.d + self.w, self.v + self.d], [self.w, -self.d]

    def strip(self, face, row):
        """Полоска в 1 пиксель на боковой грани (для торцов сегментов на сгибе)."""
        uv, size = self.side(face, row, row + 1)
        return uv, size


def mirror_faces(faces):
    """Зеркальная развёртка (как mirror у box UV): восток↔запад, каждая грань отражена по U."""
    out = {}
    swap = {'east': 'west', 'west': 'east'}
    for name, f in faces.items():
        uv, size = f['uv'], f['uv_size']
        nf = {'uv': [uv[0] + size[0], uv[1]], 'uv_size': [-size[0], size[1]]}
        out[swap.get(name, name)] = nf
    return out


def segment_faces(box, r0, r1, cap_top, cap_bottom, mirror=False):
    """Грани сегмента конечности, занимающего строки [r0, r1) развёртки.
    cap_* — с какой стороны брать полоску для торца на сгибе ('north'/'south'), если торец внутренний."""
    faces = {}
    for f in ('north', 'east', 'south', 'west'):
        uv, size = box.side(f, r0, r1)
        faces[f] = {'uv': uv, 'uv_size': size}
    if r0 == 0:
        uv, size = box.top()
    else:
        uv, size = box.strip(cap_top, r0)
    faces['up'] = {'uv': uv, 'uv_size': size}
    if r1 == box.h:
        uv, size = box.bottom()
    else:
        uv, size = box.strip(cap_bottom, r1 - 1)
    faces['down'] = {'uv': uv, 'uv_size': size}
    if mirror:
        faces = mirror_faces(faces)
    return faces


def cube(x0, y0, z0, sx, sy, sz, faces, grow=0.0, grow_top=None, grow_bottom=None):
    """Кубоид от (x0,y0,z0) размера (sx,sy,sz). grow — раздувание поперёк (броня, внешний слой);
    вдоль оси конечности раздувается только внешний торец (grow_top / grow_bottom), чтобы соседние
    сегменты не перекрывались."""
    gt = grow if grow_top is None else grow_top
    gb = grow if grow_bottom is None else grow_bottom
    return {
        'origin': [r(x0 - grow), r(y0 - gb), r(z0 - grow)],
        'size': [r(sx + 2 * grow), r(sy + gt + gb), r(sz + 2 * grow)],
        'uv': faces,
    }


def r(x):
    x = round(x, 4)
    return int(x) if x == int(x) else x


def splits(total, parts):
    rows, acc = [], 0
    for p in parts:
        rows.append((acc, acc + p))
        acc += p
    assert acc == total
    return rows


# ---------------------------------------------------------------------------------------- описание
# Части тела: (кость, родитель, точка вращения), кубоид задаётся отдельно для скина и брони.

def limb_layout(side, kind, slim=False):
    """Сегменты руки или ноги: [(кость, родитель, pivot, y0, y1, строки развёртки)]."""
    s = -1 if side == 'Right' else 1
    if kind == 'arm':
        names = [side + 'UpperArm', side + 'LowerArm', side + 'Hand']
        w = 3 if slim else 4
        # правая рука от x=-4 наружу; тонкая рука прижата к корпусу
        cx = s * (4 + w / 2)
        shoulder = [s * 5, 22, 0]
        rows = splits(12, ARM_SPLIT)
        y_top = 24
    else:
        names = [side + 'UpperLeg', side + 'LowerLeg', side + 'Foot']
        w = 4
        cx = s * 1.9
        shoulder = [s * 1.9, 12, 0]
        rows = splits(12, LEG_SPLIT)
        y_top = 12
    out = []
    parent = 'UpperTorso' if kind == 'arm' else 'LowerTorso'
    for i, (name, (r0, r1)) in enumerate(zip(names, rows)):
        y1 = y_top - r0
        y0 = y_top - r1
        pivot = shoulder if i == 0 else [cx, y1, 0]
        out.append((name, parent, [r(p) for p in pivot], y0, y1, r0, r1, cx, w))
        parent = name
    return out


def skin_model(slim):
    """Модель игрока со скином 64×64 (развёртка как у ванильного PlayerModel)."""
    bones = []

    def bone(name, parent, pivot, cubes):
        b = {'name': name, 'pivot': [r(p) for p in pivot]}
        if parent:
            b['parent'] = parent
        if cubes:
            b['cubes'] = cubes
        bones.append(b)

    # Корпус
    body = Box(16, 16, 8, 12, 4)
    jacket = Box(16, 32, 8, 12, 4)
    (u0, u1), (l0, l1) = splits(12, TORSO_SPLIT)
    bone('LowerTorso', None, [0, 12, 0], [cube(-4, 12, -2, 8, 6, 4, segment_faces(body, l0, l1, 'south', 'south'))])
    bone('LowerTorsoLayer', 'LowerTorso', [0, 12, 0],
         [cube(-4, 12, -2, 8, 6, 4, segment_faces(jacket, l0, l1, 'south', 'south'), 0.25, 0, 0.25)])
    bone('UpperTorso', 'LowerTorso', [0, 18, 0], [cube(-4, 18, -2, 8, 6, 4, segment_faces(body, u0, u1, 'south', 'south'))])
    bone('UpperTorsoLayer', 'UpperTorso', [0, 18, 0],
         [cube(-4, 18, -2, 8, 6, 4, segment_faces(jacket, u0, u1, 'south', 'south'), 0.25, 0.25, 0)])
    # Голова
    head = Box(0, 0, 8, 8, 8)
    hat = Box(32, 0, 8, 8, 8)
    bone('Head', 'UpperTorso', [0, 24, 0], [cube(-4, 24, -4, 8, 8, 8, segment_faces(head, 0, 8, 'south', 'south'))])
    bone('HeadLayer', 'Head', [0, 24, 0], [cube(-4, 24, -4, 8, 8, 8, segment_faces(hat, 0, 8, 'south', 'south'), 0.5)])

    # Руки и ноги: (сторона, вид, развёртка кожи, развёртка внешнего слоя, торцы сверху/снизу)
    w = 3 if slim else 4
    limbs = [
        ('Right', 'arm', Box(40, 16, w, 12, 4), Box(40, 32, w, 12, 4)),
        ('Left', 'arm', Box(32, 48, w, 12, 4), Box(48, 48, w, 12, 4)),
        ('Right', 'leg', Box(0, 16, 4, 12, 4), Box(0, 32, 4, 12, 4)),
        ('Left', 'leg', Box(16, 48, 4, 12, 4), Box(0, 48, 4, 12, 4)),
    ]
    for side, kind, skin, layer in limbs:
        # локоть и запястье раскрываются сзади, колено и голеностоп — спереди
        cap = 'south' if kind == 'arm' else 'north'
        segs = limb_layout(side, kind, slim)
        n = len(segs)
        for i, (name, parent, pivot, y0, y1, r0, r1, cx, wd) in enumerate(segs):
            x0 = cx - wd / 2
            bone(name, parent, pivot, [cube(x0, y0, -2, wd, y1 - y0, 4, segment_faces(skin, r0, r1, cap, cap))])
            bone(name + 'Layer', name, pivot,
                 [cube(x0, y0, -2, wd, y1 - y0, 4, segment_faces(layer, r0, r1, cap, cap), 0.25,
                       0.25 if i == 0 else 0, 0.25 if i == n - 1 else 0)])
    return geo('geometry.jujutsu_neon.player_r15' + ('_slim' if slim else ''), 64, 64, bones)


def armor_model(inner):
    """Модель брони (текстура 64×32 как у ванильного HumanoidModel). Кости — те же имена, что у игрока:
    отрисовка копирует в них позу. inner — поножи (раздувание 0.5), иначе шлем/нагрудник/ботинки (1.0)."""
    g = 0.5 if inner else 1.0
    bones = []

    def bone(name, parent, pivot, cubes):
        b = {'name': name, 'pivot': [r(p) for p in pivot]}
        if parent:
            b['parent'] = parent
        if cubes:
            b['cubes'] = cubes
        bones.append(b)

    body = Box(16, 16, 8, 12, 4)
    (u0, u1), (l0, l1) = splits(12, TORSO_SPLIT)
    bone('LowerTorso', None, [0, 12, 0], [cube(-4, 12, -2, 8, 6, 4, segment_faces(body, l0, l1, 'south', 'south'), g, 0, g)])
    bone('UpperTorso', 'LowerTorso', [0, 18, 0], [cube(-4, 18, -2, 8, 6, 4, segment_faces(body, u0, u1, 'south', 'south'), g, g, 0)])
    head = Box(0, 0, 8, 8, 8)
    hat = Box(32, 0, 8, 8, 8)
    bone('Head', 'UpperTorso', [0, 24, 0], [cube(-4, 24, -4, 8, 8, 8, segment_faces(head, 0, 8, 'south', 'south'), g)])
    bone('HeadLayer', 'Head', [0, 24, 0], [cube(-4, 24, -4, 8, 8, 8, segment_faces(hat, 0, 8, 'south', 'south'), g + 0.5)])
    arm = Box(40, 16, 4, 12, 4)
    leg = Box(0, 16, 4, 12, 4)
    for side in ('Right', 'Left'):
        for kind, box in (('arm', arm), ('leg', leg)):
            cap = 'south' if kind == 'arm' else 'north'
            segs = limb_layout(side, kind, False)
            n = len(segs)
            for i, (name, parent, pivot, y0, y1, r0, r1, cx, wd) in enumerate(segs):
                x0 = cx - wd / 2
                bone(name, parent, pivot,
                     [cube(x0, y0, -2, wd, y1 - y0, 4, segment_faces(box, r0, r1, cap, cap, mirror=(side == 'Left')), g,
                           g if i == 0 else 0, g if i == n - 1 else 0)])
    return geo('geometry.jujutsu_neon.armor_' + ('inner' if inner else 'outer'), 64, 32, bones)


def geo(identifier, tw, th, bones):
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {
                'identifier': identifier,
                'texture_width': tw,
                'texture_height': th,
                'visible_bounds_width': 4,
                'visible_bounds_height': 4,
                'visible_bounds_offset': [0, 1.5, 0],
            },
            'bones': bones,
        }],
    }


MODELS = {
    'player_r15.geo.json': lambda: skin_model(False),
    'player_r15_slim.geo.json': lambda: skin_model(True),
    'armor_r15_outer.geo.json': lambda: armor_model(False),
    'armor_r15_inner.geo.json': lambda: armor_model(True),
}

# Кости, которые анимируют (без *Layer), в порядке иерархии
ANIM_BONES = ['LowerTorso', 'UpperTorso', 'Head',
              'RightUpperArm', 'RightLowerArm', 'RightHand',
              'LeftUpperArm', 'LeftLowerArm', 'LeftHand',
              'RightUpperLeg', 'RightLowerLeg', 'RightFoot',
              'LeftUpperLeg', 'LeftLowerLeg', 'LeftFoot']


def write_all(out_dir=GEO_DIR):
    os.makedirs(out_dir, exist_ok=True)
    for name, build in MODELS.items():
        with open(os.path.join(out_dir, name), 'w', encoding='utf-8') as f:
            json.dump(build(), f, indent='\t')
            f.write('\n')
        print('wrote', os.path.relpath(os.path.join(out_dir, name), ROOT))


if __name__ == '__main__':
    write_all(sys.argv[1] if len(sys.argv) > 1 else GEO_DIR)
