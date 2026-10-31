"""Костюм Годжо из скина tools/assets/gojo_suit_skin.png (старый формат 64x32, HD x8).

  python3 tools/gen_gojo_suit.py

Пишет:
  textures/suit/gojo_suit_skin.png       — полный скин 64x64 (x8) для модели R15 при полном комплекте
  textures/suit/armor_{head,chest,legs,feet}.png — броня-«одежда» по частям (развёртка 64x32, x8)
  textures/suit/empty.png                — пустая текстура: при полном комплекте броню не рисуем
  textures/item/gojo_suit_{head,chest,legs,feet}.png — иконки предметов
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools/assets/gojo_suit_skin.png"
ASSETS = ROOT / "src/main/resources/assets/jujutsu_neon/textures"
SUIT = ASSETS / "suit"
ITEM = ASSETS / "item"

skin = Image.open(SRC).convert("RGBA")
S = skin.width // 64  # пикселей на единицу развёртки
assert skin.size == (64 * S, 32 * S), skin.size


def box(x0, y0, x1, y1):
    return x0 * S, y0 * S, x1 * S, y1 * S


def cut(*rects):
    """Новая развёртка 64x32, в которой оставлены только указанные прямоугольники (в единицах развёртки)."""
    out = Image.new("RGBA", skin.size, (0, 0, 0, 0))
    for r in rects:
        out.paste(skin.crop(box(*r)), (r[0] * S, r[1] * S))
    return out


# --- полный скин 64x64: левые рука и нога — зеркало правых, как делает сама игра со старыми скинами
full = Image.new("RGBA", (64 * S, 64 * S), (0, 0, 0, 0))
full.paste(skin, (0, 0))


def copy_rect(x, y, dx, dy, w, h):
    part = full.crop(box(x, y, x + w, y + h)).transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    full.paste(part, ((x + dx) * S, (y + dy) * S))


for args in [(4, 16, 16, 32, 4, 4), (8, 16, 16, 32, 4, 4), (0, 20, 24, 32, 4, 12), (4, 20, 16, 32, 4, 12),
             (8, 20, 8, 32, 4, 12), (12, 20, 16, 32, 4, 12), (44, 16, -8, 32, 4, 4), (48, 16, -8, 32, 4, 4),
             (40, 20, 0, 32, 4, 12), (44, 20, -8, 32, 4, 12), (48, 20, -16, 32, 4, 12), (52, 20, -8, 32, 4, 12)]:
    copy_rect(*args)

SUIT.mkdir(parents=True, exist_ok=True)
ITEM.mkdir(parents=True, exist_ok=True)
full.save(SUIT / "gojo_suit_skin.png", optimize=True)

# --- броня по частям (развёртка брони = старая развёртка скина 64x32)
# ботинки — нижние 2 ряда ноги и подошва; штаны — нога выше ботинок
cut((0, 0, 64, 16)).save(SUIT / "armor_head.png", optimize=True)
cut((16, 16, 40, 32), (40, 16, 56, 32)).save(SUIT / "armor_chest.png", optimize=True)
cut((4, 16, 8, 20), (0, 20, 16, 30)).save(SUIT / "armor_legs.png", optimize=True)
cut((8, 16, 12, 20), (0, 30, 16, 32)).save(SUIT / "armor_feet.png", optimize=True)
Image.new("RGBA", (64, 32), (0, 0, 0, 0)).save(SUIT / "empty.png", optimize=True)


# --- иконки: передние грани частей, по центру квадрата 16x16
def icon(name, parts):
    """parts: (x, y, w, h, куда x, куда y) в единицах развёртки."""
    canvas = Image.new("RGBA", (16 * S, 16 * S), (0, 0, 0, 0))
    for x, y, w, h, tx, ty in parts:
        canvas.alpha_composite(skin.crop(box(x, y, x + w, y + h)), (tx * S, ty * S))
    canvas.save(ITEM / f"gojo_suit_{name}.png", optimize=True)


icon("head", [(8, 8, 8, 8, 4, 4), (40, 8, 8, 8, 4, 4)])
icon("chest", [(44, 20, 4, 12, 2, 2), (20, 20, 8, 12, 6, 2), (44, 20, 4, 12, 10, 2)])
icon("legs", [(4, 20, 4, 10, 4, 3), (4, 20, 4, 10, 8, 3)])
icon("feet", [(4, 28, 4, 4, 3, 6), (4, 28, 4, 4, 9, 6)])
print("ok", full.size)
