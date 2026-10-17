#!/usr/bin/env python3
"""Сверка покадровой анимации с референсом: кадр видео ↔ поза модели, кадр в кадр.

    python3 compare.py anim.json --video ref.mp4 --start 1.25 --view side
    python3 compare.py anim.json --ref-dir кадры/ --view front --overlay 0.45

Видео режется ровно на fps анимации (ffmpeg), начиная с --start секунд; кадр N анимации
сопоставляется с кадром N референса. --crop x:y:w:h — вырезать область персонажа из видео.
Результат: <out>/<имя>_compare.png — пары «референс | модель» (или наложение при --overlay)
с номерами кадров, и <имя>_compare.gif.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jnanim import FrameAnim  # noqa: E402
from preview import render, _font  # noqa: E402


def extract(video, fps, start, count, crop, tmp):
    vf = f"fps={fps}"
    if crop:
        vf = f"crop={crop}," + vf
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(start), "-i", str(video), "-vf", vf,
                    "-frames:v", str(count), str(Path(tmp) / "f_%04d.png")], check=True)
    return sorted(Path(tmp).glob("f_*.png"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("anim")
    ap.add_argument("--video")
    ap.add_argument("--ref-dir")
    ap.add_argument("--start", type=float, default=0.0)
    ap.add_argument("--crop", default="")
    ap.add_argument("--view", default="front")
    ap.add_argument("--overlay", type=float, default=0.0, help="наложить модель поверх кадра с этой прозрачностью")
    ap.add_argument("--cols", type=int, default=4)
    ap.add_argument("--size", type=int, default=260, help="высота клетки")
    ap.add_argument("--out", default="compare_out")
    a = ap.parse_args()

    anim = FrameAnim.load(a.anim)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        if a.video:
            refs = extract(a.video, anim.fps, a.start, len(anim), a.crop, tmp)
        elif a.ref_dir:
            refs = sorted(p for p in Path(a.ref_dir).iterdir() if p.suffix.lower() in (".png", ".jpg", ".jpeg"))
        else:
            ap.error("нужно --video или --ref-dir")
        n = min(len(anim), len(refs))
        h = a.size
        cells = []
        for i in range(n):
            ref = Image.open(refs[i]).convert("RGB")
            ref = ref.resize((max(1, int(ref.width * h / ref.height)), h))
            model = render(anim[i], a.view, (int(h * 0.75), h), h / 52.0)
            if a.overlay > 0:
                m = model.resize((ref.width, h))
                cell = Image.blend(ref, m, a.overlay)
            else:
                cell = Image.new("RGB", (ref.width + model.width, h))
                cell.paste(ref, (0, 0))
                cell.paste(model, (ref.width, 0))
            ImageDraw.Draw(cell).text((4, 4), str(i), fill=(255, 255, 0), font=_font())
            cells.append(cell)
        if not cells:
            print("нет кадров")
            return
        cw = max(c.width for c in cells)
        cols = min(a.cols, len(cells))
        rows = (len(cells) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cw, rows * h), (10, 10, 12))
        for i, c in enumerate(cells):
            sheet.paste(c, ((i % cols) * cw, (i // cols) * h))
        p = out / f"{anim.name}_compare.png"
        sheet.save(p)
        g = out / f"{anim.name}_compare.gif"
        cells[0].save(g, save_all=True, append_images=cells[1:], duration=int(1000 / anim.fps), loop=0)
        print(p)
        print(g)


if __name__ == "__main__":
    main()
