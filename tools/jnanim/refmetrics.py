#!/usr/bin/env python3
"""Автозамеры референса по каждому кадру (для сверки катсцен с видео).

    python3 refmetrics.py кадры/ --fps 30 --out metrics.csv

По каждому кадру: яркость, склейка (разница гистограмм с прошлым кадром), движение,
сдвиг камеры (фазовая корреляция), и для масок цветов эффектов — синий, красный,
фиолетовый, белый, чёрный — доля площади, центр (0..1) и размах (ширина/высота доли кадра).
Те же замеры можно снять и с кадров из игры — compare сравнит их столбец в столбец.
"""
import argparse
import csv
import sys
from pathlib import Path

import cv2
import numpy as np

MASKS = ("blue", "red", "purple", "white", "black")


def masks(hsv):
    h = hsv[..., 0].astype(np.float32) * 2.0      # 0..360
    s = hsv[..., 1].astype(np.float32) / 255.0
    v = hsv[..., 2].astype(np.float32) / 255.0
    return {
        # небо (h≈212, s≈0.5, v≈0.65) не попадает: синий эффект ярче
        "blue": (h >= 180) & (h <= 250) & (s > 0.38) & (v > 0.80),
        "red": ((h <= 20) | (h >= 336)) & (s > 0.45) & (v > 0.28),
        "purple": (h > 255) & (h < 336) & (s > 0.28) & (v > 0.25),
        "white": (v > 0.92) & (s < 0.14),
        "black": v < 0.07,
    }


def blob(m):
    """Самое крупное пятно маски: центр, радиус (доля высоты кадра)."""
    if not m.any():
        return -1.0, -1.0, 0.0
    mm = cv2.dilate(m.astype(np.uint8), np.ones((3, 3), np.uint8))
    n, lab, st, cen = cv2.connectedComponentsWithStats(mm, connectivity=8)
    if n <= 1:
        return -1.0, -1.0, 0.0
    k = 1 + int(np.argmax(st[1:, cv2.CC_STAT_AREA]))
    H, W = m.shape
    r = float(np.sqrt(st[k, cv2.CC_STAT_AREA] / np.pi)) / H
    return cen[k][0] / W, cen[k][1] / H, r


def stats(m):
    a = float(m.mean())
    if a < 1e-4:
        return a, -1.0, -1.0, 0.0, 0.0
    ys, xs = np.nonzero(m)
    H, W = m.shape
    cx, cy = xs.mean() / W, ys.mean() / H
    # размах по 10..90 перцентилям — устойчиво к искрам
    sx = (np.percentile(xs, 90) - np.percentile(xs, 10)) / W
    sy = (np.percentile(ys, 90) - np.percentile(ys, 10)) / H
    return a, cx, cy, sx, sy


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("frames")
    ap.add_argument("--fps", type=float, default=30.0)
    ap.add_argument("--out", default="metrics.csv")
    ap.add_argument("--cut", type=float, default=0.45, help="порог склейки (0..1)")
    a = ap.parse_args()
    files = sorted(p for p in Path(a.frames).iterdir() if p.suffix.lower() in (".jpg", ".png", ".jpeg"))
    if not files:
        sys.exit("нет кадров")
    prev_hist = prev_gray = None
    rows = []
    for i, f in enumerate(files):
        img = cv2.imread(str(f))
        img = cv2.resize(img, (320, 180), interpolation=cv2.INTER_AREA)
        hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
        hist = cv2.calcHist([hsv], [0, 1, 2], None, [18, 4, 4], [0, 180, 0, 256, 0, 256])
        hist = cv2.normalize(hist, None).flatten()
        r = {"frame": i, "f60": i * 2, "t": round(i / a.fps, 4),
             "luma": round(float(gray.mean()), 4), "contrast": round(float(gray.std()), 4)}
        b, g, rr = img[..., 0].mean(), img[..., 1].mean(), img[..., 2].mean()
        r["rgb"] = f"{int(rr)},{int(g)},{int(b)}"
        if prev_hist is None:
            r["cut"], r["motion"], r["dx"], r["dy"] = 1.0, 0.0, 0.0, 0.0
        else:
            r["cut"] = round(float(cv2.compareHist(prev_hist, hist, cv2.HISTCMP_BHATTACHARYYA)), 4)
            r["motion"] = round(float(np.abs(gray - prev_gray).mean()), 4)
            win = cv2.createHanningWindow(gray.shape[::-1], cv2.CV_32F)
            (dx, dy), resp = cv2.phaseCorrelate(prev_gray, gray, win)
            ok = resp > 0.08
            r["dx"] = round(dx / gray.shape[1], 4) if ok else ""
            r["dy"] = round(dy / gray.shape[0], 4) if ok else ""
        blur = cv2.GaussianBlur(gray, (0, 0), 4)
        _, mx, _, loc = cv2.minMaxLoc(blur)
        r["hot_x"], r["hot_y"], r["hot_v"] = round(loc[0] / 320, 3), round(loc[1] / 180, 3), round(mx, 3)
        for name, m in masks(hsv).items():
            ar, cx, cy, sx, sy = stats(m)
            r[f"{name}_a"] = round(ar, 4)
            r[f"{name}_x"] = round(cx, 3)
            r[f"{name}_y"] = round(cy, 3)
            r[f"{name}_w"] = round(sx, 3)
            r[f"{name}_h"] = round(sy, 3)
            if name in ("blue", "red", "purple", "white"):
                bx, by, br = blob(m)
                r[f"{name}_bx"], r[f"{name}_by"], r[f"{name}_br"] = round(bx, 3), round(by, 3), round(br, 3)
        r["is_cut"] = int(r["cut"] >= a.cut)
        rows.append(r)
        prev_hist, prev_gray = hist, gray
    with open(a.out, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(a.out, len(rows), "кадров;", sum(r["is_cut"] for r in rows[1:]), "склеек")


if __name__ == "__main__":
    main()
