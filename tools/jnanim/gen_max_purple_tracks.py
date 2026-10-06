#!/usr/bin/env python3
"""Треки Максимального Фиолетового из замеров референса → Java (MaxPurpleRefTracks).

    python3 gen_max_purple_tracks.py docs/max_purple_metrics.csv

Для планов, где в кадре в основном шары (небо, сближение, «космос»), берём по каждому настоящему
кадру видео: где на экране Красный и Синий (центр 0..1, радиус в долях высоты) и как поворачивалась
камера (из сдвига картинки между кадрами). В игре шары ставятся ровно в эти точки экрана
относительно камеры кат-сцены, а камера поворачивается так же, как в видео, — кадр в кадр.
"""
import csv
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/java/com/kira/jujutsuneon/MaxPurpleRefTracks.java"

# имя, первый и последний кадр (шкала 60 к/с), вертикальный угол обзора камеры в игре,
# брать ли поворот камеры из видео
SEGMENTS = [
    ("SKY", 218, 380, 60.0, True),
    ("APPROACH", 382, 476, 66.0, False),  # сдвиг картинки здесь «тянет» крупный Красный — камера неподвижна
    ("SPACE", 916, 994, 60.0, False),
]
MIN_AREA = {"red": 0.0006, "blue": 0.0008}


def fill(vals, ok):
    """Линейно заполнить пропуски (ok=False) по соседним хорошим значениям."""
    n = len(vals)
    idx = [i for i in range(n) if ok[i]]
    if not idx:
        return [0.0] * n
    out = list(vals)
    for i in range(n):
        if ok[i]:
            continue
        lo = max((j for j in idx if j < i), default=None)
        hi = min((j for j in idx if j > i), default=None)
        if lo is None:
            out[i] = vals[hi]
        elif hi is None:
            out[i] = vals[lo]
        else:
            k = (i - lo) / (hi - lo)
            out[i] = vals[lo] * (1 - k) + vals[hi] * k
    return out


def median3(v):
    return [sorted(v[max(0, i - 1):i + 2])[len(v[max(0, i - 1):i + 2]) // 2] for i in range(len(v))]


def mean(v, r):
    return [sum(v[max(0, i - r):i + r + 1]) / len(v[max(0, i - r):i + r + 1]) for i in range(len(v))]


def build(rows, name, a, b, fov, cam):
    seg = [r for r in rows if a <= int(r["f60"]) <= b]
    ch = {}
    for orb in ("red", "blue"):
        ok = [float(r[f"{orb}_a"]) >= MIN_AREA[orb] and float(r[f"{orb}_bx"]) >= 0 for r in seg]
        # «космос»: Красный тусклый и местами теряется — только уверенные кадры
        if name == "SPACE" and orb == "red":
            ok = [o and float(r["red_a"]) >= 0.005 for o, r in zip(ok, seg)]
        for c, key in (("x", "bx"), ("y", "by"), ("r", "br")):
            v = fill([float(r[f"{orb}_{key}"]) for r in seg], ok)
            v = median3(v)
            v = mean(v, 2 if c == "r" else 1)
            ch[orb[0] + c] = v
    f60 = [int(r["f60"]) for r in seg]
    if name == "APPROACH":
        # 468+: Синий проходит сквозь камеру, Красный за голубыми клубами в центре
        for i, f in enumerate(f60):
            if f >= 468:
                k = min(1.0, (f - 466) / 10.0)
                ch["bx"][i] = ch["bx"][i - 1] * 0.7 + 0.30 * 0.3
                ch["by"][i] = ch["by"][i - 1] * 0.7 + 0.40 * 0.3
                ch["br"][i] = 0.38 + 0.62 * k
                ch["rx"][i] = ch["rx"][i - 1] * 0.6 + 0.42 * 0.4
                ch["ry"][i] = ch["ry"][i - 1] * 0.6 + 0.52 * 0.4
                ch["rr"][i] = ch["rr"][i - 1] * 0.6 + 0.17 * 0.4
    tv = math.tan(math.radians(fov) / 2)
    th = tv * 16.0 / 9.0
    yaw, pitch = [0.0], [0.0]
    if cam:
        dx = [float(r["dx"]) if r["dx"] not in ("", None) else None for r in seg]
        dy = [float(r["dy"]) if r["dy"] not in ("", None) else None for r in seg]
        okx = [v is not None and abs(v) < 0.08 for v in dx]
        dx = fill([v or 0.0 for v in dx], okx)
        dy = fill([v or 0.0 for v in dy], okx)
        dx[0] = dy[0] = 0.0
        for i in range(1, len(seg)):
            # картинка уехала вправо → камера повернулась влево (yaw меньше);
            # вниз → камера поднялась (pitch меньше)
            yaw.append(yaw[-1] - math.degrees(math.atan(dx[i] * 2 * th)))
            pitch.append(pitch[-1] - math.degrees(math.atan(dy[i] * 2 * tv)))
    else:
        yaw = [0.0] * len(seg)
        pitch = [0.0] * len(seg)
    ch["yaw"], ch["pitch"] = yaw, pitch
    return f60[0], f60[-1], fov, ch


def jarr(v, prec=4):
    return "{" + ", ".join(f"{x:.{prec}f}f" for x in v) + "}"


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs/max_purple_metrics.csv"
    rows = list(csv.DictReader(open(src)))
    parts = []
    for name, a, b, fov, cam in SEGMENTS:
        f0, f1, fv, ch = build(rows, name, a, b, fov, cam)
        body = "\n".join(f"            {jarr(ch[k], 3 if k in ('yaw', 'pitch') else 4)}, // {k}"
                         for k in ("rx", "ry", "rr", "bx", "by", "br", "yaw", "pitch"))
        parts.append(f"    static final Track {name} = new Track({f0}, {f1}, {fv:.1f}f, new float[][]{{\n{body}\n    }});")
    java = f'''package com.kira.jujutsuneon;

/**
 * Треки референса Максимального Фиолетового по настоящим кадрам видео (30 к/с).
 * СГЕНЕРИРОВАНО tools/jnanim/gen_max_purple_tracks.py из docs/max_purple_metrics.csv — руками не править.
 *
 * Каналы: rx, ry, rr — Красный (центр на экране 0..1 и радиус в долях высоты кадра), bx, by, br — Синий,
 * yaw, pitch — поворот камеры от начала плана (градусы, как в игре).
 */
final class MaxPurpleRefTracks {{

    static final int RX = 0, RY = 1, RR = 2, BX = 3, BY = 4, BR = 5, YAW = 6, PITCH = 7;

    static final class Track {{
        /** Первый и последний кадр плана по шкале 60 к/с (настоящие кадры — через один). */
        final int f60From, f60To;
        final float fov;
        final float[][] ch;

        Track(int f60From, int f60To, float fov, float[][] ch) {{
            this.f60From = f60From;
            this.f60To = f60To;
            this.fov = fov;
            this.ch = ch;
        }}

        /** Начало и конец плана во времени кат-сцены (тики по 0,05 с). */
        double from() {{
            return f60From / 3.0;
        }}

        double to() {{
            return (f60To + 2) / 3.0;
        }}

        boolean covers(double t) {{
            return t >= from() && t < to();
        }}

        /** Значение канала в момент t: между кадрами видео — плавно (внутри одного плана). */
        double at(int c, double t) {{
            float[] v = ch[c];
            double k = (t * 3.0 - f60From) / 2.0;
            if (k <= 0.0) return v[0];
            if (k >= v.length - 1) return v[v.length - 1];
            int i = (int) Math.floor(k);
            double u = k - i;
            double p0 = v[Math.max(0, i - 1)], p1 = v[i], p2 = v[i + 1], p3 = v[Math.min(v.length - 1, i + 2)];
            double u2 = u * u, u3 = u2 * u;
            return 0.5 * (2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2
                    + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
        }}
    }}

{chr(10).join(parts)}

    static final Track[] ALL = {{{", ".join(s[0] for s in SEGMENTS)}}};

    static Track at(double t) {{
        for (Track tr : ALL) if (tr.covers(t)) return tr;
        return null;
    }}

    private MaxPurpleRefTracks() {{
    }}
}}
'''
    OUT.write_text(java, encoding="utf-8")
    print(OUT)
    for name, a, b, fov, cam in SEGMENTS:
        f0, f1, fv, ch = build(rows, name, a, b, fov, cam)
        print(name, f"кадров {len(ch['rx'])}; камера yaw {ch['yaw'][-1]:+.1f}° pitch {min(ch['pitch']):+.1f}..{max(ch['pitch']):+.1f}°")


if __name__ == "__main__":
    main()
