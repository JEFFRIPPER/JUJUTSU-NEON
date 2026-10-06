#!/usr/bin/env python3
"""Служебные покадровые анимации (в моде, только для проверки системы командой /jnanim):

  jn_calibration — по секунде на каждую ось: сравнить знаки в игре с превью (preview.py).
                   В кадрах — события с подписью, что сейчас должно происходить.
  jn_example_frames — пример плотного формата: 24 кадра, каждый задан вручную.
  jn_example_keys   — пример формата с ключами и кривыми (запекается при загрузке).

Сами техники мода эти файлы не используют.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jnanim import FrameAnim  # noqa: E402

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/jujutsu_neon/frame_animations"
OUT.mkdir(parents=True, exist_ok=True)

STEPS = [
    ("правая рука вперёд (pitch -90)", {"rightArm.pitch": -90}),
    ("правая рука наружу (roll +90)", {"rightArm.roll": 90}),
    ("правая рука внутрь (yaw -40, pitch -90)", {"rightArm.pitch": -90, "rightArm.yaw": -40}),
    ("правый локоть (bend -90)", {"rightArm.pitch": -45, "rightArm.bend": -90}),
    ("левая рука вперёд (pitch -90)", {"leftArm.pitch": -90}),
    ("левая рука наружу (roll -90)", {"leftArm.roll": -90}),
    ("голова вниз (pitch +30)", {"head.pitch": 30}),
    ("голова влево (yaw +45)", {"head.yaw": 45}),
    ("корпус вперёд (torso.bend +30)", {"torso.bend": 30}),
    ("правая нога вперёд (pitch -60)", {"rightLeg.pitch": -60}),
    ("правое колено (bend +90)", {"rightLeg.pitch": -45, "rightLeg.bend": 90}),
    ("всё тело назад (body.pitch +30)", {"body.pitch": 30}),
    ("всё тело влево (body.yaw +45)", {"body.yaw": 45}),
    ("всё тело завал влево (body.roll +20)", {"body.roll": 20}),
    ("присесть (body.y -0.3)", {"body.y": -0.3, "rightLeg.pitch": -50, "rightLeg.bend": 100,
                                 "leftLeg.pitch": -50, "leftLeg.bend": 100, "torso.bend": 20}),
]

NEUTRAL = {"rightArm.pitch": 0, "rightArm.yaw": 0, "rightArm.roll": 0, "rightArm.bend": 0,
           "leftArm.pitch": 0, "leftArm.yaw": 0, "leftArm.roll": 0, "leftArm.bend": 0,
           "rightLeg.pitch": 0, "rightLeg.bend": 0, "leftLeg.pitch": 0, "leftLeg.bend": 0,
           "head.pitch": 0, "head.yaw": 0, "torso.bend": 0,
           "body.pitch": 0, "body.yaw": 0, "body.roll": 0, "body.y": 0}


def calibration():
    a = FrameAnim("jn_calibration", fps=24, length=24 * len(STEPS) + 1, fade_in=0.1, fade_out=0.2)
    a.key(0, NEUTRAL)
    for i, (label, pose) in enumerate(STEPS):
        f0 = i * 24
        p = dict(NEUTRAL)
        p.update(pose)
        a.key(f0 + 6, p, "inoutsine")
        a.key(f0 + 16, p)
        a.key(f0 + 24, NEUTRAL, "inoutsine")
        a.event(f0 + 6, "step", label)
    a.bake()
    a.save(OUT / "jn_calibration.json")


def example_frames():
    # Каждый из 24 кадров задан явно (так выглядит покадровая анимация «из референса»).
    a = FrameAnim("jn_example_frames", fps=24, length=24, fade_in=0.08, fade_out=0.15)
    jab = [0, -8, -22, -40, -62, -80, -92, -95, -93, -90, -86, -82, -77, -71, -64, -56, -47, -38, -30, -22, -15, -9, -4, 0]
    elbow = [-6, -30, -70, -100, -95, -60, -25, -4, -2, -4, -8, -14, -22, -30, -38, -44, -48, -46, -40, -32, -24, -16, -10, -6]
    twist = [0, 4, 8, 10, 6, -6, -14, -17, -16, -14, -12, -10, -8, -6, -5, -4, -3, -2, -2, -1, -1, 0, 0, 0]
    for f in range(24):
        a[f]["rightArm.pitch"] = jab[f]
        a[f]["rightArm.bend"] = elbow[f]
        a[f]["torso.yaw"] = twist[f]
        a[f]["head.pitch"] = 2 if 6 <= f <= 12 else 0
    a.event(7, "hit")
    a.save(OUT / "jn_example_frames.json")


def example_keys():
    data = {
        "format": "jn_keys",
        "name": "jn_example_keys",
        "fps": 24,
        "length": 36,
        "fade_in": 0.1,
        "fade_out": 0.2,
        "keys": [
            {"frame": 0, "pose": {"leftArm.pitch": 0, "leftArm.roll": 0, "leftArm.bend": 0}},
            {"frame": 8, "ease": "outback", "pose": {"leftArm.pitch": -170, "leftArm.roll": -10, "leftArm.bend": -10}},
            {"frame": 12, "ease": "inoutsine", "pose": {"leftArm.roll": -30}},
            {"frame": 16, "ease": "inoutsine", "pose": {"leftArm.roll": 5}},
            {"frame": 20, "ease": "inoutsine", "pose": {"leftArm.roll": -30}},
            {"frame": 24, "ease": "inoutsine", "pose": {"leftArm.roll": 5}},
            {"frame": 35, "ease": [0.4, 0.0, 0.2, 1.0], "pose": {"leftArm.pitch": 0, "leftArm.roll": 0, "leftArm.bend": 0}},
        ],
        "tracks": {"head.yaw": [[0, 0], [10, 12, "outquad"], [30, 12], [35, 0, "inoutsine"]]},
        "secondary": {"enabled": True, "arms": 1.0, "breathing": 1.0, "micro": 1.0},
        "events": [{"frame": 8, "name": "wave_start"}],
    }
    (OUT / "jn_example_keys.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


calibration()
example_frames()
example_keys()
print("ok")
