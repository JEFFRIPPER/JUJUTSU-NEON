"""Скины для превью (свои, нарисованные кодом): preview.png — персонаж в форме, uvtest.png — проверка развёртки."""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'skins')


def box_regions(u, v, w, h, d):
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h),
    }


def fill(img, rect, col):
    x, y, w, h = rect
    ImageDraw.Draw(img).rectangle([x, y, x + w - 1, y + h - 1], fill=col)


def uvtest():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    parts = {
        'head': ((0, 0, 8, 8, 8), (220, 60, 60)),
        'body': ((16, 16, 8, 12, 4), (60, 160, 220)),
        'rarm': ((40, 16, 4, 12, 4), (240, 170, 40)),
        'larm': ((32, 48, 4, 12, 4), (150, 90, 230)),
        'rleg': ((0, 16, 4, 12, 4), (60, 200, 90)),
        'lleg': ((16, 48, 4, 12, 4), (230, 90, 170)),
    }
    shade = {'front': 1.0, 'back': 0.45, 'right': 0.8, 'left': 0.65, 'top': 1.2, 'bottom': 0.3}
    d = ImageDraw.Draw(img)
    for name, (box, col) in parts.items():
        for face, rect in box_regions(*box).items():
            k = shade[face]
            c = tuple(min(255, int(x * k)) for x in col) + (255,)
            fill(img, rect, c)
            x, y, w, h = rect
            if face == 'front':
                # полоса сверху (белая) и снизу (чёрная) — видно, не перевёрнута ли грань
                d.line([x, y, x + w - 1, y], fill=(255, 255, 255, 255))
                d.line([x, y + h - 1, x + w - 1, y + h - 1], fill=(0, 0, 0, 255))
                # левый край (правая сторона персонажа) — жёлтый
                d.line([x, y, x, y + h - 1], fill=(255, 255, 0, 255))
    img.save(os.path.join(OUT, 'uvtest.png'))


def preview():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = (236, 200, 168, 255)
    skin_d = (214, 176, 144, 255)
    hair = (238, 240, 246, 255)
    hair_d = (200, 206, 220, 255)
    cloth = (34, 36, 52, 255)
    cloth_d = (24, 25, 38, 255)
    cloth_l = (52, 56, 78, 255)
    band = (18, 18, 22, 255)
    shoe = (16, 16, 18, 255)
    gold = (210, 180, 90, 255)
    d = ImageDraw.Draw(img)

    # голова
    head = box_regions(0, 0, 8, 8, 8)
    for f in ('front', 'right', 'left', 'back', 'bottom'):
        fill(img, head[f], skin)
    fill(img, head['top'], hair)
    fx, fy, _, _ = head['front']
    fill(img, (fx, fy, 8, 2), hair)                    # чёлка
    fill(img, (fx, fy + 2, 1, 2), hair)
    fill(img, (fx + 7, fy + 2, 1, 2), hair)
    fill(img, (fx, fy + 3, 8, 2), band)                # повязка
    d.point((fx + 3, fy + 6), fill=skin_d)
    d.line([fx + 3, fy + 6, fx + 4, fy + 6], fill=(170, 110, 100, 255))  # рот
    for f in ('right', 'left'):
        x, y, w, h = head[f]
        fill(img, (x, y, w, 3), hair)
        fill(img, (x, y + 3, w, 2), band)
        fill(img, (x + (0 if f == 'left' else w - 3), y + 5, 3, 1), hair)
    bx, by, _, _ = head['back']
    fill(img, (bx, by, 8, 6), hair)
    fill(img, (bx, by + 3, 8, 2), band)

    # шапка (внешний слой головы): объём волос
    hat = box_regions(32, 0, 8, 8, 8)
    fill(img, hat['top'], hair_d)
    x, y, w, h = hat['front']
    for i in range(0, 8, 2):
        fill(img, (x + i, y, 1, 2), hair)
    for f in ('right', 'left', 'back'):
        x, y, w, h = hat[f]
        fill(img, (x, y, w, 2), hair_d)
        for i in range(0, w, 3):
            fill(img, (x + i, y + 2, 1, 1), hair)

    # корпус
    body = box_regions(16, 16, 8, 12, 4)
    for f, c in (('front', cloth), ('back', cloth_d), ('right', cloth_d), ('left', cloth_d), ('top', cloth), ('bottom', cloth_d)):
        fill(img, body[f], c)
    x, y, w, h = body['front']
    d.line([x + 4, y + 1, x + 4, y + h - 1], fill=cloth_l)  # молния
    fill(img, (x + 2, y + 7, 4, 1), cloth_l)                # пояс
    d.point((x + 4, y + 7), fill=gold)
    # куртка (внешний слой): высокий воротник
    jacket = box_regions(16, 32, 8, 12, 4)
    x, y, w, h = jacket['front']
    fill(img, (x, y, w, 2), cloth_l)
    fill(img, (x + 3, y, 2, 2), (0, 0, 0, 0))
    for f in ('right', 'left', 'back'):
        x, y, w, h = jacket[f]
        fill(img, (x, y, w, 2), cloth_l)

    # руки
    for u, v in ((40, 16), (32, 48)):
        arm = box_regions(u, v, 4, 12, 4)
        for f in ('front', 'back', 'right', 'left'):
            x, y, w, h = arm[f]
            fill(img, (x, y, w, 9), cloth if f == 'front' else cloth_d)
            fill(img, (x, y + 9, w, 1), cloth_l)            # манжета
            fill(img, (x, y + 10, w, 2), skin)              # кисть
        fill(img, arm['top'], cloth)
        fill(img, arm['bottom'], skin_d)
    # ноги
    for u, v in ((0, 16), (16, 48)):
        leg = box_regions(u, v, 4, 12, 4)
        for f in ('front', 'back', 'right', 'left'):
            x, y, w, h = leg[f]
            fill(img, (x, y, w, 10), cloth if f == 'front' else cloth_d)
            fill(img, (x, y + 10, w, 2), shoe)
        fill(img, leg['top'], cloth)
        fill(img, leg['bottom'], shoe)
        x, y, w, h = leg['front']
        fill(img, (x, y + 5, w, 1), cloth_l)  # колено
    img.save(os.path.join(OUT, 'preview.png'))


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    uvtest()
    preview()
    print('ok')
